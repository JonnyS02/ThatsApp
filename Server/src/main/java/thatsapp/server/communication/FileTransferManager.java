package thatsapp.server.communication;

import javafx.application.Platform;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;
import thatsapp.common.Logger;
import thatsapp.server.messages.ServerLogMessages;
import thatsapp.server.ui.controllers.MainController;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class FileTransferManager {

    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final String FILE_TRANSFER_DISABLED_REASON = "File transfer disabled";

    private final Server server;
    private final Path baseDir;
    private final Consumer<String> logSink;
    private final Map<String, FileSession> sessions = new HashMap<>();
    private final ExecutorService transferExecutor;

    public FileTransferManager(Server server) {
        this(server, text -> {
            MainController controller = server.getController();
            if (controller != null) {
                Platform.runLater(() -> controller.appendMessage(text));
            }
        }, Executors.newCachedThreadPool());
    }

    FileTransferManager(Server server, Consumer<String> logSink) {
        this(server, logSink, Executors.newCachedThreadPool());
    }

    FileTransferManager(Server server, Consumer<String> logSink, ExecutorService transferExecutor) {
        this.server = server;
        this.logSink = logSink;
        this.transferExecutor = transferExecutor;
        this.baseDir = Paths.get(System.getProperty("java.io.tmpdir"), "thatsapp-files");
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_CREATING_TEMP_DIR_PREFIX, e), e);
        }
        cleanupStaleTempFiles();
    }

    public synchronized void handlePacketFromClient(int clientId, FilePacket packet) {
        if (packet == null) {
            Logger.warn(ServerLogMessages.rejectedFilePacketMissing(clientId));
            return;
        }
        String status = packet.status();
        if (status == null || status.isBlank()) {
            Logger.warn(ServerLogMessages.rejectedFilePacketStatusMissing(clientId));
            return;
        }
        switch (status) {
            case StatusCodes.FILE_META -> handleMeta(clientId, packet);
            case StatusCodes.FILE_CHUNK -> handleChunk(clientId, packet);
            case StatusCodes.FILE_COMPLETE -> handleComplete(clientId, packet);
            case StatusCodes.FILE_CANCEL -> handleCancel(clientId, packet);
            case StatusCodes.FILE_ACK -> handleAck(clientId, packet);
            case StatusCodes.FILE_REQUEST -> handleRequest(clientId, packet);
            default -> Logger.warn(ServerLogMessages.rejectedFilePacketUnknownStatus(clientId, status));
        }
        cleanupFinished();
    }

    public synchronized void clientDisconnected(int clientId) {
        sessions.values().removeIf(session -> {
            if (session.uploaderId == clientId) {
                session.canceled = true;
                broadcastToAllRecipients(session, new FilePacket(StatusCodes.FILE_CANCEL, session.fileId, session.fileName, session.fileSize, 0, session.totalChunks, "", clientId, "Uploader disconnected"));
                deleteTemp(session);
                log(ServerLogMessages.uploadCanceledUploaderDisconnected(session.fileName));
                return true;
            }
            session.recipients.remove(clientId);
            return false;
        });
        notifyAll();
        cleanupFinished();
    }

    public synchronized void cleanupAll() {
        for (FileSession session : sessions.values()) {
            session.canceled = true;
            deleteTemp(session);
        }
        sessions.clear();
        notifyAll();
    }

    private void handleMeta(int uploaderId, FilePacket packet) {
        long fileSizeBytesLimit = server.getRules().fileSizeBytesLimit();
        int fileCountLimit = server.getRules().fileCountLimit();
        if (packet.fileId() == null || packet.fileId().isBlank() || sessions.containsKey(packet.fileId())) {
            sendToClient(uploaderId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", uploaderId, "Invalid file id"));
            log(ServerLogMessages.rejectedUploadInvalidFileId(uploaderId));
            return;
        }
        if (packet.fileSize() < 0) {
            sendToClient(uploaderId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", uploaderId, "Invalid file size"));
            log(ServerLogMessages.rejectedUploadInvalidFileSize(uploaderId));
            return;
        }
        cleanupFinished();
        if (fileCountLimit < 0) {
            sendToClient(uploaderId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", uploaderId, FILE_TRANSFER_DISABLED_REASON));
            log(ServerLogMessages.rejectedUploadFileTransferDisabled(uploaderId, packet.fileName()));
            return;
        }
        if (fileCountLimit > 0 && sessions.size() >= fileCountLimit) {
            String reason = "File count limit reached";
            sendToClient(uploaderId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", uploaderId, reason));
            log(ServerLogMessages.rejectedUploadFileCountLimitReached(uploaderId, packet.fileName(), fileCountLimit));
            return;
        }
        if (fileSizeBytesLimit > 0 && packet.fileSize() > fileSizeBytesLimit) {
            String reason = "File exceeds limit of " + server.getRules().fileSizeBytesLimitInMb() + " MB.";
            sendToClient(uploaderId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", uploaderId, reason));
            log(ServerLogMessages.rejectedUploadExceedsLimit(uploaderId, packet.fileName(), packet.fileSize(), fileSizeBytesLimit));
            return;
        }
        if (!isChunkCountValid(packet.fileSize(), packet.totalChunks())) {
            sendToClient(uploaderId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", uploaderId, "Invalid chunk count"));
            log(ServerLogMessages.rejectedUploadInvalidChunkCount(uploaderId, packet.fileName()));
            return;
        }
        long maxChunkBytes = maxChunkBytes(packet.fileSize(), packet.totalChunks());
        long maxPayloadChars = maxPayloadChars(maxChunkBytes);
        FileSession session = new FileSession(packet.fileId(), packet.fileName(), packet.fileSize(), packet.totalChunks(), maxPayloadChars, uploaderId, createTempPath(), server.getClientIds());
        sessions.put(packet.fileId(), session);
        log(ServerLogMessages.uploadStartedFromClient(uploaderId, packet.fileName(), packet.fileSize()));
        broadcastOffer(session);
    }

    private void handleChunk(int uploaderId, FilePacket packet) {
        FileSession session = sessions.get(packet.fileId());
        if (session == null || session.canceled) {
            return;
        }
        String violation = validateChunk(session, uploaderId, packet);
        if (violation != null) {
            cancelSession(session, uploaderId, violation);
            return;
        }
        appendChunk(session, packet.payload());
        notifyAll();
    }

    private void handleComplete(int uploaderId, FilePacket packet) {
        FileSession session = sessions.get(packet.fileId());
        if (session == null) {
            return;
        }
        if (session.canceled) {
            return;
        }
        if (uploaderId != session.uploaderId) {
            cancelSession(session, uploaderId, "Unexpected uploader");
            return;
        }
        if (packet.totalChunks() != session.totalChunks || packet.fileSize() != session.fileSize) {
            cancelSession(session, uploaderId, "File metadata mismatch");
            return;
        }
        if (session.totalChunks > 0 && session.receivedChunks < session.totalChunks) {
            cancelSession(session, uploaderId, "Upload completed before all chunks were received");
            return;
        }
        session.uploadFinished = true;
        notifyAll();
        log(ServerLogMessages.uploadFinishedFromClient(uploaderId, packet.fileName()));
        cleanupFinished();
    }

    private void handleCancel(int clientId, FilePacket packet) {
        FileSession session = sessions.get(packet.fileId());
        if (session == null) {
            return;
        }
        if (clientId == session.uploaderId) {
            session.canceled = true;
            broadcastToAllRecipients(session, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", clientId, "Upload canceled"));
            deleteTemp(session);
            sessions.remove(packet.fileId());
            notifyAll();
            log(ServerLogMessages.uploadCanceledByClient(clientId, packet.fileName()));
            return;
        }
        session.recipients.remove(clientId);
        notifyAll();
        log(ServerLogMessages.downloadCanceledByClient(clientId, packet.fileName()));
        cleanupFinished();
    }

    private void handleAck(int clientId, FilePacket packet) {
        FileSession session = sessions.get(packet.fileId());
        if (session == null) {
            return;
        }
        session.recipients.remove(clientId);
        notifyAll();
        log(ServerLogMessages.downloadFinishedForClient(clientId, session.fileName));
        cleanupFinished();
    }

    private void handleRequest(int clientId, FilePacket packet) {
        if (!server.isFileTransferEnabled()) {
            sendToClient(clientId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", 0, FILE_TRANSFER_DISABLED_REASON));
            return;
        }
        FileSession session = sessions.get(packet.fileId());
        if (session == null || session.canceled) {
            sendToClient(clientId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", 0, "File no longer available"));
            return;
        }
        if (!session.eligibleClients.contains(clientId)) {
            sendToClient(clientId, new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", 0, "Not eligible for this file"));
            return;
        }
        if (!session.recipients.add(clientId)) {
            return;
        }
        log(ServerLogMessages.downloadStartedForClient(clientId, session.fileName, session.fileSize));
        session.pendingClients.remove(clientId);
        sendToClient(clientId, new FilePacket(StatusCodes.FILE_META, session.fileId, session.fileName, session.fileSize, -1, session.totalChunks, "", session.uploaderId, ""));
        transferExecutor.submit(() -> replayBufferedChunks(session, clientId));
    }

    static boolean isChunkCountValid(long fileSize, int totalChunks) {
        if (fileSize == 0) {
            return totalChunks == 0;
        }
        if (fileSize < 0 || totalChunks <= 0) {
            return false;
        }
        return totalChunks <= fileSize;
    }

    static long maxChunkBytes(long fileSize, int totalChunks) {
        if (fileSize <= 0 || totalChunks <= 0) {
            return 0;
        }
        return (fileSize + totalChunks - 1) / totalChunks;
    }

    private static long base64Length(long bytes) {
        if (bytes <= 0) {
            return 0;
        }
        return ((bytes + 2) / 3) * 4;
    }

    static long maxPayloadChars(long maxChunkBytes) {
        if (maxChunkBytes <= 0) {
            return 0;
        }
        long base64Len = base64Length(maxChunkBytes);
        long encryptedBytesLen = base64Len + IV_BYTES + TAG_BYTES;
        long encryptedPayloadLen = base64Length(encryptedBytesLen);
        return Math.max(base64Len, encryptedPayloadLen);
    }

    private String validateChunk(FileSession session, int uploaderId, FilePacket packet) {
        if (session.uploadFinished) {
            return "Upload already completed";
        }
        if (uploaderId != session.uploaderId) {
            return "Unexpected uploader";
        }
        if (packet.fileSize() != session.fileSize) {
            return "File size mismatch";
        }
        if (packet.totalChunks() != session.totalChunks) {
            return "Chunk count mismatch";
        }
        if (session.totalChunks <= 0) {
            return "Unexpected chunk for empty file";
        }
        int chunkIndex = packet.chunkIndex();
        if (chunkIndex < 0 || chunkIndex >= session.totalChunks) {
            return "Invalid chunk index";
        }
        if (session.receivedChunks >= session.totalChunks) {
            return "Too many chunks";
        }
        if (chunkIndex != session.receivedChunks) {
            return "Unexpected chunk order";
        }
        String payload = packet.payload();
        if (payload == null) {
            return "Missing payload";
        }
        if ((long) payload.length() > session.maxPayloadChars) {
            return "Chunk payload too large";
        }
        return null;
    }

    private void cancelSession(FileSession session, int uploaderId, String reason) {
        if (session.canceled) {
            return;
        }
        session.canceled = true;
        FilePacket cancel = new FilePacket(StatusCodes.FILE_CANCEL, session.fileId, session.fileName, session.fileSize, 0, session.totalChunks, "", session.uploaderId, reason);
        broadcastToAllRecipients(session, cancel);
        sendToClient(session.uploaderId, cancel);
        notifyAll();
        log(ServerLogMessages.uploadCanceledFromClient(uploaderId, session.fileName, reason));
        cleanupFinished();
    }

    private void appendChunk(FileSession session, String payload) {
        try {
            Files.writeString(session.tempPath, payload + System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            session.receivedChunks++;
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_BUFFERING_FILE_PREFIX, e), e);
        }
    }

    private void replayBufferedChunks(FileSession session, int clientId) {
        int sent = 0;
        long nextOffset = 0L;
        while (session.recipients.contains(clientId) && !session.canceled) {
            int available;
            boolean uploadFinished;
            synchronized (this) {
                available = session.receivedChunks;
                uploadFinished = session.uploadFinished;
            }
            if (sent < available) {
                ChunkReplayResult result = sendChunkRange(session, clientId, sent, available, nextOffset);
                sent += result.sentChunks();
                nextOffset = result.nextOffset();
                continue;
            }
            if (uploadFinished && available >= session.totalChunks && session.recipients.contains(clientId)) {
                sendToClient(clientId, new FilePacket(StatusCodes.FILE_COMPLETE, session.fileId, session.fileName, session.fileSize, session.totalChunks, session.totalChunks, "", session.uploaderId, ""));
                return;
            }
            synchronized (this) {
                while (sent == session.receivedChunks
                        && !session.uploadFinished
                        && session.recipients.contains(clientId)
                        && !session.canceled) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private ChunkReplayResult sendChunkRange(FileSession session, int clientId, int startIndex, int endIndex, long startOffset) {
        if (session.canceled || !session.recipients.contains(clientId) || startIndex >= endIndex) {
            return new ChunkReplayResult(0, startOffset);
        }
        int sent = 0;
        long nextOffset = startOffset;
        try (RandomAccessFile reader = new RandomAccessFile(session.tempPath.toFile(), "r")) {
            // File payloads are Base64 strings, so byte offsets remain stable across line reads.
            String line;
            int index = startIndex;
            reader.seek(startOffset);
            while (index < endIndex && (line = reader.readLine()) != null) {
                if (session.canceled || !session.recipients.contains(clientId)) {
                    return new ChunkReplayResult(sent, nextOffset);
                }
                sendToClient(clientId, new FilePacket(StatusCodes.FILE_CHUNK, session.fileId, session.fileName, session.fileSize, index, session.totalChunks, line, session.uploaderId, ""));
                index++;
                sent++;
                nextOffset = reader.getFilePointer();
            }
            if (index < endIndex && !session.canceled && session.recipients.contains(clientId)) {
                throw new IOException("Buffered chunk missing");
            }
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_REPLAYING_BUFFERED_FILE_PREFIX, e), e);
            sendToClient(clientId, new FilePacket(StatusCodes.FILE_CANCEL, session.fileId, session.fileName, session.fileSize, 0, session.totalChunks, "", session.uploaderId, "Download failed"));
            synchronized (this) {
                session.recipients.remove(clientId);
                notifyAll();
            }
        }
        return new ChunkReplayResult(sent, nextOffset);
    }

    private void broadcastOffer(FileSession session) {
        FilePacket meta = new FilePacket(StatusCodes.FILE_META, session.fileId, session.fileName, session.fileSize, -1, session.totalChunks, "", session.uploaderId, "");
        for (Integer clientId : server.getClientIds()) {
            if (clientId != session.uploaderId) {
                sendToClient(clientId, meta);
            }
        }
    }

    private void broadcastToAllRecipients(FileSession session, FilePacket packet) {
        for (Integer clientId : session.recipients) {
            sendToClient(clientId, packet);
        }
    }

    private void sendToClient(int clientId, FilePacket packet) {
        ClientHandler handler = server.getClient(clientId);
        if (handler != null) {
            handler.sendFilePacket(packet);
        }
    }

    private void cleanupFinished() {
        sessions.values().removeIf(session -> {
            if (session.canceled) {
                deleteTemp(session);
                return true;
            }
            boolean uploadComplete = session.uploadFinished;
            boolean noActiveRecipients = session.recipients.isEmpty();
            boolean allTried = session.pendingClients.isEmpty();
            if (uploadComplete && noActiveRecipients && allTried) {
                deleteTemp(session);
                return true;
            }
            return false;
        });
    }

    private void deleteTemp(FileSession session) {
        try {
            Files.deleteIfExists(session.tempPath);
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_DELETING_TEMP_FILE_PREFIX, e), e);
        }
    }

    private void cleanupStaleTempFiles() {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path path : stream) {
                deletePath(path);
            }
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_CLEANING_TEMP_DIR_PREFIX, e), e);
        }
    }

    private void deletePath(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_DELETING_TEMP_FILE_PREFIX, e), e);
                        }
                    });
                }
                return;
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_DELETING_TEMP_FILE_PREFIX, e), e);
        }
    }

    private void log(String text) {
        Logger.info(text);
        logSink.accept(text);
    }

    private Path createTempPath() {
        Path path = baseDir.resolve(UUID.randomUUID() + ".dat").normalize();
        if (!path.startsWith(baseDir)) {
            throw new IllegalStateException("Temp path escaped base directory");
        }
        return path;
    }

    private record ChunkReplayResult(int sentChunks, long nextOffset) {
    }

    private static final class FileSession {
        private final String fileId;
        private final String fileName;
        private final long fileSize;
        private final int totalChunks;
        private final long maxPayloadChars;
        private final int uploaderId;
        private final Path tempPath;
        private final Set<Integer> recipients = ConcurrentHashMap.newKeySet();
        private final Set<Integer> eligibleClients;
        private final Set<Integer> pendingClients;
        private volatile int receivedChunks = 0;
        private volatile boolean uploadFinished = false;
        private volatile boolean canceled = false;

        private FileSession(
                String fileId,
                String fileName,
                long fileSize,
                int totalChunks,
                long maxPayloadChars,
                int uploaderId,
                Path tempPath,
                Set<Integer> currentClients
        ) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.totalChunks = totalChunks;
            this.maxPayloadChars = maxPayloadChars;
            this.uploaderId = uploaderId;
            this.tempPath = tempPath;
            this.eligibleClients = ConcurrentHashMap.newKeySet();
            this.eligibleClients.addAll(currentClients);
            this.eligibleClients.remove(uploaderId);
            this.pendingClients = ConcurrentHashMap.newKeySet();
            this.pendingClients.addAll(eligibleClients);
        }
    }
}
