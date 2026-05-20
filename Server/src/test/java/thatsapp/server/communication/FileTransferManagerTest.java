package thatsapp.server.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.common.FilePacket;
import thatsapp.common.ServerRules;
import thatsapp.common.StatusCodes;
import thatsapp.server.ui.controllers.MainController;

class FileTransferManagerTest {

    private static final String SETTINGS_FILE_PROPERTY = "thatsapp.server.settingsFile";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(SETTINGS_FILE_PROPERTY);
    }

    @Test
    void validatesChunkSizingHelpers() {
        assertTrue(FileTransferManager.isChunkCountValid(0, 0));
        assertFalse(FileTransferManager.isChunkCountValid(10, 0));
        assertEquals(4, FileTransferManager.maxChunkBytes(10, 3));
        assertTrue(FileTransferManager.maxPayloadChars(4) >= 8);
    }

    @Test
    void servesRequestedUploadsAndCleansUpAfterAcknowledgement() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler receiver = new RecordingClientHandler(2);
        ExecutorService transferExecutor = Executors.newCachedThreadPool();
        server.addClient(uploader);
        server.addClient(receiver);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        }, transferExecutor);
        try {
            FilePacket meta = new FilePacket(StatusCodes.FILE_META, "file-1", "report.txt", 5, -1, 1, "", 1, "");

            manager.handlePacketFromClient(1, meta);
            awaitPacketCount(receiver, 1);
            manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_REQUEST, "file-1", "report.txt", 5, -1, 1, "", 2, ""));
            awaitPacketCount(receiver, 2);
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-1", "report.txt", 5, 0, 1, "payload", 1, ""));
            awaitPacketCount(receiver, 3);
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_COMPLETE, "file-1", "report.txt", 5, 1, 1, "", 1, ""));
            awaitPacketCount(receiver, 4);
            manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_ACK, "file-1", "report.txt", 5, 1, 1, "", 2, ""));
            manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_REQUEST, "file-1", "report.txt", 5, -1, 1, "", 2, ""));
            awaitPacketCount(receiver, 5);

            assertEquals(StatusCodes.FILE_META, receiver.packets.get(0).status());
            assertEquals(StatusCodes.FILE_CHUNK, receiver.packets.get(2).status());
            assertEquals(StatusCodes.FILE_COMPLETE, receiver.packets.get(3).status());
            assertEquals(StatusCodes.FILE_CANCEL, receiver.packets.get(4).status());
            assertEquals("File no longer available", receiver.packets.get(4).reason());
            manager.cleanupAll();
        } finally {
            transferExecutor.shutdownNow();
        }
    }

    @Test
    void sendsRequestedChunksInOrderWhenDownloadStartsDuringUpload() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler receiver = new RecordingClientHandler(2);
        ExecutorService transferExecutor = Executors.newCachedThreadPool();
        server.addClient(uploader);
        server.addClient(receiver);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        }, transferExecutor);
        try {
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-mid-upload", "report.txt", 5, -1, 5, "", 1, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-mid-upload", "report.txt", 5, 0, 5, "payload-0", 1, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-mid-upload", "report.txt", 5, 1, 5, "payload-1", 1, ""));
            manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_REQUEST, "file-mid-upload", "report.txt", 5, -1, 5, "", 2, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-mid-upload", "report.txt", 5, 2, 5, "payload-2", 1, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-mid-upload", "report.txt", 5, 3, 5, "payload-3", 1, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-mid-upload", "report.txt", 5, 4, 5, "payload-4", 1, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_COMPLETE, "file-mid-upload", "report.txt", 5, 5, 5, "", 1, ""));
            awaitPacketCount(receiver, 8);

            List<Integer> chunkIndexes = receiver.packets.stream()
                    .filter(packet -> StatusCodes.FILE_CHUNK.equals(packet.status()))
                    .map(FilePacket::chunkIndex)
                    .toList();

            assertEquals(List.of(0, 1, 2, 3, 4), chunkIndexes);
            assertEquals(StatusCodes.FILE_COMPLETE, receiver.packets.get(7).status());
            manager.cleanupAll();
        } finally {
            transferExecutor.shutdownNow();
        }
    }

    @Test
    void ignoresDuplicateDownloadRequestsForActiveRecipient() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler receiver = new RecordingClientHandler(2);
        ExecutorService transferExecutor = Executors.newCachedThreadPool();
        server.addClient(uploader);
        server.addClient(receiver);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        }, transferExecutor);
        try {
            FilePacket request = new FilePacket(StatusCodes.FILE_REQUEST, "file-duplicate-request", "report.txt", 2, -1, 2, "", 2, "");

            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-duplicate-request", "report.txt", 2, -1, 2, "", 1, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-duplicate-request", "report.txt", 2, 0, 2, "payload-0", 1, ""));
            manager.handlePacketFromClient(2, request);
            manager.handlePacketFromClient(2, request);
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-duplicate-request", "report.txt", 2, 1, 2, "payload-1", 1, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_COMPLETE, "file-duplicate-request", "report.txt", 2, 2, 2, "", 1, ""));
            awaitPacketCount(receiver, 5);

            List<Integer> chunkIndexes = receiver.packets.stream()
                    .filter(packet -> StatusCodes.FILE_CHUNK.equals(packet.status()))
                    .map(FilePacket::chunkIndex)
                    .toList();

            assertEquals(5, receiver.packets.size());
            assertEquals(List.of(0, 1), chunkIndexes);
            assertEquals(StatusCodes.FILE_COMPLETE, receiver.packets.getLast().status());
            manager.cleanupAll();
        } finally {
            transferExecutor.shutdownNow();
        }
    }

    @Test
    void cancelsSessionOnInvalidChunkOrder() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler receiver = new RecordingClientHandler(2);
        ExecutorService transferExecutor = Executors.newCachedThreadPool();
        server.addClient(uploader);
        server.addClient(receiver);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        }, transferExecutor);
        try {
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-2", "report.txt", 5, -1, 2, "", 1, ""));
            manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_REQUEST, "file-2", "report.txt", 5, -1, 2, "", 2, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-2", "report.txt", 5, 1, 2, "payload", 1, ""));
            awaitPacketCount(uploader, 1);
            awaitPacketCount(receiver, 3);

            assertEquals(StatusCodes.FILE_CANCEL, uploader.packets.getFirst().status());
            assertEquals("Unexpected chunk order", uploader.packets.getFirst().reason());
            assertEquals(StatusCodes.FILE_CANCEL, receiver.packets.get(2).status());
            manager.cleanupAll();
        } finally {
            transferExecutor.shutdownNow();
        }
    }

    @Test
    void rejectsUploadsWhenFileCountLimitIsReached() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        server.updateRules(new ServerRules(10, 1, 1024 * 1024, 200));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler secondUploader = new RecordingClientHandler(2);
        server.addClient(uploader);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        });

        manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-limit-1", "one.txt", 5, -1, 1, "", 1, ""));
        server.addClient(secondUploader);
        manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_META, "file-limit-2", "two.txt", 5, -1, 1, "", 2, ""));
        awaitPacketCount(secondUploader, 1);

        assertEquals(StatusCodes.FILE_CANCEL, secondUploader.packets.getFirst().status());
        assertEquals("File count limit reached", secondUploader.packets.getFirst().reason());
        manager.cleanupAll();
    }

    @Test
    void rejectsUploadsWhenFileTransferIsDisabled() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        server.updateRules(new ServerRules(10, -1, 0, 200));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        server.addClient(uploader);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        });

        manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-disabled", "blocked.txt", 5, -1, 1, "", 1, ""));
        awaitPacketCount(uploader, 1);

        assertEquals(StatusCodes.FILE_CANCEL, uploader.packets.getFirst().status());
        assertEquals("File transfer disabled", uploader.packets.getFirst().reason());
        manager.cleanupAll();
    }

    @Test
    void rejectsDownloadRequestsFromClientsThatWereNotEligibleWhenUploadStarted() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler eligibleReceiver = new RecordingClientHandler(2);
        RecordingClientHandler stranger = new RecordingClientHandler(3);
        server.addClient(uploader);
        server.addClient(eligibleReceiver);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        });

        manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-3", "report.txt", 5, -1, 1, "", 1, ""));
        server.addClient(stranger);
        manager.handlePacketFromClient(3, new FilePacket(StatusCodes.FILE_REQUEST, "file-3", "report.txt", 5, -1, 1, "", 3, ""));
        awaitPacketCount(stranger, 1);

        assertEquals(StatusCodes.FILE_CANCEL, stranger.packets.getFirst().status());
        assertEquals("Not eligible for this file", stranger.packets.getFirst().reason());
        manager.cleanupAll();
    }

    @Test
    void cancelsUploadWhenCompletedBeforeAllChunksWereReceived() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler receiver = new RecordingClientHandler(2);
        ExecutorService transferExecutor = Executors.newCachedThreadPool();
        server.addClient(uploader);
        server.addClient(receiver);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        }, transferExecutor);
        try {
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-4", "report.txt", 5, -1, 2, "", 1, ""));
            manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_REQUEST, "file-4", "report.txt", 5, -1, 2, "", 2, ""));
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-4", "report.txt", 5, 0, 2, "payload", 1, ""));
            awaitPacketCount(receiver, 3);
            manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_COMPLETE, "file-4", "report.txt", 5, 2, 2, "", 1, ""));
            awaitPacketCount(uploader, 1);
            awaitPacketCount(receiver, 4);

            assertEquals(StatusCodes.FILE_CANCEL, uploader.packets.getFirst().status());
            assertEquals("Upload completed before all chunks were received", uploader.packets.getFirst().reason());
            assertEquals(StatusCodes.FILE_CANCEL, receiver.packets.get(3).status());
            assertEquals("Upload completed before all chunks were received", receiver.packets.get(3).reason());
            manager.cleanupAll();
        } finally {
            transferExecutor.shutdownNow();
        }
    }

    @Test
    void notifiesRecipientsWhenUploaderDisconnects() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler receiver = new RecordingClientHandler(2);
        server.addClient(uploader);
        server.addClient(receiver);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        });

        manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-5", "report.txt", 5, -1, 1, "", 1, ""));
        manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_REQUEST, "file-5", "report.txt", 5, -1, 1, "", 2, ""));
        awaitPacketCount(receiver, 2);
        manager.clientDisconnected(1);
        awaitPacketCount(receiver, 3);

        assertEquals(StatusCodes.FILE_CANCEL, receiver.packets.get(2).status());
        assertEquals("Uploader disconnected", receiver.packets.get(2).reason());
        manager.cleanupAll();
    }

    @Test
    void doesNotReplayBufferedChunksAfterUploaderDisconnectDeletesTempFile() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingClientHandler uploader = new RecordingClientHandler(1);
        RecordingClientHandler receiver = new RecordingClientHandler(2);
        TestTransferExecutor transferExecutor = new TestTransferExecutor();
        server.addClient(uploader);
        server.addClient(receiver);
        FileTransferManager manager = new FileTransferManager(server, text -> {
        }, transferExecutor);

        manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_META, "file-6", "report.txt", 10, -1, 2, "", 1, ""));
        manager.handlePacketFromClient(1, new FilePacket(StatusCodes.FILE_CHUNK, "file-6", "report.txt", 10, 0, 2, "payload", 1, ""));
        manager.handlePacketFromClient(2, new FilePacket(StatusCodes.FILE_REQUEST, "file-6", "report.txt", 10, -1, 2, "", 2, ""));
        awaitPacketCount(receiver, 2);
        manager.clientDisconnected(1);
        awaitPacketCount(receiver, 3);

        transferExecutor.runAll();

        assertEquals(3, receiver.packets.size());
        assertEquals(StatusCodes.FILE_CANCEL, receiver.packets.get(2).status());
        assertEquals("Uploader disconnected", receiver.packets.get(2).reason());
        manager.cleanupAll();
    }

    private static void awaitPacketCount(RecordingClientHandler handler, int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (handler.packets.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(handler.packets.size() >= expectedCount, "Expected at least " + expectedCount + " packets but got " + handler.packets.size());
    }

    private static final class TestServer extends Server {
        private final Map<Integer, RecordingClientHandler> clients = new HashMap<>();
        private final Set<Integer> clientIds = new HashSet<>();
        private ServerRules rules = new ServerRules(10, 10, 1024 * 1024, 200);

        private TestServer(Path settingsFile) {
            super(prepare(settingsFile));
        }

        private void addClient(RecordingClientHandler handler) {
            clients.put(handler.clientId(), handler);
            clientIds.add(handler.clientId());
        }

        @Override
        public synchronized ServerRules getRules() {
            return rules;
        }

        @Override
        public synchronized void updateRules(ServerRules rules) {
            this.rules = rules;
        }

        @Override
        public ClientHandler getClient(int clientId) {
            return clients.get(clientId);
        }

        @Override
        public Set<Integer> getClientIds() {
            return new HashSet<>(clientIds);
        }

        @Override
        public MainController getController() {
            return null;
        }

        private static MainController prepare(Path settingsFile) {
            System.setProperty(SETTINGS_FILE_PROPERTY, settingsFile.toString());
            return null;
        }
    }

    private static final class RecordingClientHandler extends ClientHandler {
        private final int id;
        private final List<FilePacket> packets = new CopyOnWriteArrayList<>();

        private RecordingClientHandler(int id) {
            super(null, id, null, "");
            this.id = id;
        }

        private int clientId() {
            return id;
        }

        @Override
        public void sendFilePacket(FilePacket packet) {
            packets.add(packet);
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class TestTransferExecutor extends AbstractExecutorService {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }
}
