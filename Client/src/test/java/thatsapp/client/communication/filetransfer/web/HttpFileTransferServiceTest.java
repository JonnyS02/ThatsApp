package thatsapp.client.communication.filetransfer.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingUi;
import thatsapp.client.communication.filetransfer.common.AbstractFileTransferService;
import thatsapp.client.communication.transport.web.TestWebserver;
import thatsapp.client.communication.transport.web.WebserverTransport;
import thatsapp.client.messages.ClientAttentionMessages;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

class HttpFileTransferServiceTest {

    private static final long TUS_CHUNK_SIZE = 8L * 1024L * 1024L;

    @TempDir
    Path tempDir;

    @Test
    void rejectsIncomingFilesAboveConfiguredLimit() {
        RecordingUi ui = new RecordingUi(tempDir, false);
        WebserverTransport transport = configuredTransport("http://localhost", "room", "token", new SymmetricEncryption("", "session"));
        HttpFileTransferService service = new HttpFileTransferService(() -> transport, ui, id -> "Alex");
        service.setFileSizeBytesLimit(3);

        service.handleIncoming(new FilePacket(StatusCodes.FILE_META, "file-1", "name.txt", 5, -1, 1, "http://localhost/file", 7, ""));

        assertEquals(ClientAttentionMessages.fileExceedsLimit(0), ui.lastError);
        assertFalse(ui.incomingShown);
    }

    @Test
    void showsPromptAndUsesFallbackNameWhenDecryptionFails() {
        RecordingUi ui = new RecordingUi(tempDir, false);
        WebserverTransport transport = configuredTransport("http://localhost", "room", "token", new SymmetricEncryption("1234567890abcdef", "session"));
        HttpFileTransferService service = new HttpFileTransferService(() -> transport, ui, id -> "Alex");

        service.handleIncoming(new FilePacket(StatusCodes.FILE_META, "file-2", "broken-name", 4, -1, 1, "http://localhost/file", 7, ""));

        assertTrue(ui.incomingShown);
        assertEquals("Unknown file", ui.lastIncomingFileName);
        assertEquals("Alex", ui.lastSenderName);
        assertTrue(ui.incomingView("Unknown file").promptShown);
        assertEquals(AbstractFileTransferService.STATUS_START_DOWNLOAD, ui.incomingView("Unknown file").progressLabel);
    }

    @Test
    void autoDownloadMarksIncomingViewCanceledWhenSessionTokenIsMissing() throws Exception {
        RecordingUi ui = new RecordingUi(tempDir, true);
        WebserverTransport transport = configuredTransport("http://127.0.0.1:1", "room", "", new SymmetricEncryption("", "session"));
        HttpFileTransferService service = new HttpFileTransferService(() -> transport, ui, id -> "Alex");

        service.handleIncoming(new FilePacket(StatusCodes.FILE_META, "file-3", "name.txt", 4, -1, 1, "http://127.0.0.1:1/file", 7, ""));

        assertTrue(ui.incomingView("name.txt").awaitCanceled());
        assertTrue(ui.incomingView("name.txt").canceledReason.contains("Missing session token"));
    }

    @Test
    void uploadMarksOutgoingViewCanceledWhenSessionTokenIsMissing() throws Exception {
        RecordingUi ui = new RecordingUi(tempDir, false);
        WebserverTransport transport = configuredTransport("http://127.0.0.1:1", "room", "", new SymmetricEncryption("", "session"));
        HttpFileTransferService service = new HttpFileTransferService(() -> transport, ui, id -> "Alex");
        File file = Files.writeString(tempDir.resolve("upload.txt"), "hello").toFile();

        service.enqueueUploads(List.of(file));

        assertTrue(ui.outgoingView("upload.txt").awaitCanceled());
        assertTrue(ui.outgoingView("upload.txt").canceledReason.contains("Missing session token"));
    }

    @Test
    void uploadUsesSinglePatchRequestAtChunkBoundary() throws Exception {
        RecordingUi ui = new RecordingUi(tempDir, false);
        File file = tempDir.resolve("boundary.txt").toFile();
        createAsciiFile(file.toPath(), TUS_CHUNK_SIZE);

        try (TestWebserver server = TestWebserver.upload("token")) {
            WebserverTransport transport = configuredTransport(server.baseUrl(), "room", "token", new SymmetricEncryption("", "session"));
            HttpFileTransferService service = new HttpFileTransferService(() -> transport, ui, id -> "Alex");

            service.enqueueUploads(List.of(file));

            assertTrue(ui.outgoingView("boundary.txt").awaitFinished());
            assertEquals("", ui.outgoingView("boundary.txt").canceledReason);
            assertEquals(1, server.createRequests);
            assertEquals(1, server.chunkRequests);
            assertEquals("PATCH", server.chunkMethod);
            assertEquals(TUS_CHUNK_SIZE, server.receivedChunkBytes);
        }
    }

    private static WebserverTransport configuredTransport(String baseUrl, String sessionName, String token, SymmetricEncryption symmetric) {
        try {
            WebserverTransport transport = new WebserverTransport();
            setField(transport, "baseUrl", baseUrl);
            setField(transport, "sessionName", sessionName);
            setField(transport, "token", token);
            setField(transport, "symmetric", symmetric);
            return transport;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = WebserverTransport.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void createAsciiFile(Path path, long size) throws IOException {
        byte[] block = "a".repeat(8192).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = Files.newOutputStream(path)) {
            long remaining = size;
            while (remaining > 0) {
                int length = (int) Math.min(block.length, remaining);
                out.write(block, 0, length);
                remaining -= length;
            }
        }
    }
}
