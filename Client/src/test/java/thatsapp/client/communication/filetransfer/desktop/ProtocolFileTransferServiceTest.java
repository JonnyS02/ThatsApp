package thatsapp.client.communication.filetransfer.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingClient;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingUi;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingView;
import thatsapp.client.communication.filetransfer.common.AbstractFileTransferService;
import thatsapp.client.test.FxTestSupport;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

class ProtocolFileTransferServiceTest {

    @BeforeAll
    static void initToolkit() throws Exception {
        FxTestSupport.initToolkit();
    }

    @TempDir
    Path tempDir;

    @Test
    void rejectsIncomingFilesAboveLimit() {
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        ProtocolFileTransferService service = new ProtocolFileTransferService(() -> client, ui, id -> "Alex");
        service.setFileSizeBytesLimit(3);

        service.handleIncoming(new FilePacket(StatusCodes.FILE_META, "file-1", "name.txt", 5, -1, 1, "", 7, ""));

        assertEquals(1, client.packets.size());
        assertEquals(StatusCodes.FILE_CANCEL, client.packets.getFirst().status());
        assertEquals("File exceeds limit", client.packets.getFirst().reason());
        assertFalse(ui.incomingShown);
    }

    @Test
    void promptsUserAndStartsDownloadOnDemand() throws Exception {
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        ProtocolFileTransferService service = new ProtocolFileTransferService(() -> client, ui, id -> "Alex");
        FilePacket meta = new FilePacket(StatusCodes.FILE_META, "file-2", "greeting.txt", 11, -1, 1, "", 7, "");

        service.handleIncoming(meta);

        assertTrue(ui.incomingShown);
        assertEquals(AbstractFileTransferService.STATUS_START_DOWNLOAD, ui.incomingView("greeting.txt").progressLabel);
        assertEquals("greeting.txt", ui.lastIncomingFileName);

        ui.incomingView("greeting.txt").downloadPrompt.run();
        assertEquals(StatusCodes.FILE_REQUEST, client.packets.getFirst().status());

        service.handleIncoming(new FilePacket(StatusCodes.FILE_CHUNK, "file-2", "greeting.txt", 11, 0, 1, encode("Hello World"), 7, ""));
        service.handleIncoming(new FilePacket(StatusCodes.FILE_COMPLETE, "file-2", "greeting.txt", 11, 1, 1, "", 7, ""));
        waitFor(() -> client.packets.size() >= 2);

        assertEquals(StatusCodes.FILE_ACK, client.packets.get(1).status());
        assertEquals("Hello World", Files.readString(tempDir.resolve("greeting.txt"), StandardCharsets.UTF_8));
        assertTrue(ui.incomingView("greeting.txt").finished);
    }

    @Test
    void usesFallbackNameWhenEncryptedFileNameCannotBeRead() {
        RecordingClient client = new RecordingClient(new SymmetricEncryption("1234567890abcdef", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        ProtocolFileTransferService service = new ProtocolFileTransferService(() -> client, ui, id -> "Alex");

        service.handleIncoming(new FilePacket(StatusCodes.FILE_META, "file-3", "not-valid", 1, -1, 1, "", 7, ""));

        assertTrue(ui.incomingShown);
        assertEquals("Unknown file", ui.lastIncomingFileName);
    }

    @Test
    void marksPendingDownloadsAsCanceledWhenServerCancels() {
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        ProtocolFileTransferService service = new ProtocolFileTransferService(() -> client, ui, id -> "Alex");
        service.handleIncoming(new FilePacket(StatusCodes.FILE_META, "file-4", "name.txt", 1, -1, 1, "", 7, ""));

        service.handleIncoming(new FilePacket(StatusCodes.FILE_CANCEL, "file-4", "name.txt", 1, 0, 1, "", 7, "Gone"));

        assertEquals("Canceled: Gone", ui.incomingView("name.txt").canceledReason);
    }

    @Test
    void marksActiveUploadsAsCanceledWhenServerCancels() throws Exception {
        Path path = Files.writeString(tempDir.resolve("outgoing.txt"), "data", StandardCharsets.UTF_8);
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        ProtocolFileTransferService service = new ProtocolFileTransferService(() -> client, ui, id -> "Alex");
        UploadTask task = new UploadTask(path.toFile(), 4, () -> client, ui, queuedTask -> {
        }, fileId -> {
        });
        task.showQueuedWaiting();
        activeUploads(service).put(task.fileId(), task);

        service.handleIncoming(new FilePacket(StatusCodes.FILE_CANCEL, task.fileId(), "outgoing.txt", path.toFile().length(), 0, 1, "", 7, "File count limit reached"));

        assertEquals("File count limit reached", ui.outgoingView("outgoing.txt").canceledReason);
        assertFalse(activeUploads(service).containsKey(task.fileId()));
    }

    @Test
    void autoDownloadsImmediatelyWithoutShowingPrompt() throws Exception {
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, true);
        ProtocolFileTransferService service = new ProtocolFileTransferService(() -> client, ui, id -> "Alex");
        FilePacket meta = new FilePacket(StatusCodes.FILE_META, "file-5", "auto.txt", 4, -1, 1, "", 7, "");

        service.handleIncoming(meta);

        assertTrue(ui.incomingShown);
        assertFalse(ui.incomingView("auto.txt").promptShown);
        assertEquals(StatusCodes.FILE_REQUEST, client.packets.getFirst().status());

        service.handleIncoming(new FilePacket(StatusCodes.FILE_CHUNK, "file-5", "auto.txt", 4, 0, 1, encode("Auto"), 7, ""));
        service.handleIncoming(new FilePacket(StatusCodes.FILE_COMPLETE, "file-5", "auto.txt", 4, 1, 1, "", 7, ""));
        waitFor(() -> client.packets.size() >= 2);

        assertEquals(StatusCodes.FILE_ACK, client.packets.get(1).status());
        assertEquals("Auto", Files.readString(tempDir.resolve("auto.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void marksQueuedDownloadsAsCanceledWhenServerCancelsThem() {
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, true);
        ProtocolFileTransferService service = new ProtocolFileTransferService(() -> client, ui, id -> "Alex");

        for (int i = 1; i <= 11; i++) {
            service.handleIncoming(new FilePacket(StatusCodes.FILE_META, "file-" + i, "queued-" + i + ".txt", 4, -1, 1, "", 7, ""));
        }

        assertEquals(10, client.packets.size());
        RecordingView queuedView = ui.incomingView("queued-11.txt");
        assertEquals(AbstractFileTransferService.STATUS_WAITING_FOR_PREVIOUS_DOWNLOADS, queuedView.progressLabel);

        service.handleIncoming(new FilePacket(StatusCodes.FILE_CANCEL, "file-11", "queued-11.txt", 4, 0, 1, "", 7, "Gone"));

        assertEquals("Canceled: Gone", queuedView.canceledReason);
        assertEquals(10, client.packets.size());
        service.cancelAll();
    }

    private static String encode(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void waitFor(Check condition) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.matches() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.matches());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, UploadTask> activeUploads(ProtocolFileTransferService service) throws Exception {
        Field field = ProtocolFileTransferService.class.getDeclaredField("activeUploads");
        field.setAccessible(true);
        return (Map<String, UploadTask>) field.get(service);
    }

    @FunctionalInterface
    private interface Check {
        boolean matches();
    }
}
