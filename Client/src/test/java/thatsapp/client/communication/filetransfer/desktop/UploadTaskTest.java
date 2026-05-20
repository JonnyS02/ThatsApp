package thatsapp.client.communication.filetransfer.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingClient;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingUi;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

class UploadTaskTest {

    @TempDir
    Path tempDir;

    @Test
    void uploadsMetaChunksAndCompletion() throws Exception {
        Path path = Files.writeString(tempDir.resolve("upload.txt"), "Hello World", StandardCharsets.UTF_8);
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        AtomicReference<String> finishedFileId = new AtomicReference<>();
        UploadTask task = new UploadTask(
                path.toFile(),
                4,
                () -> client,
                ui,
                queuedTask -> {
                },
                finishedFileId::set
        );

        task.showQueuedWaiting();
        task.run();

        assertEquals(task.fileId(), finishedFileId.get());
        assertTrue(ui.outgoingView("upload.txt").finished);
        assertEquals(StatusCodes.FILE_META, client.packets.getFirst().status());
        assertEquals(StatusCodes.FILE_COMPLETE, client.packets.getLast().status());
        assertEquals(5, client.packets.size());
        assertEquals("Hello World", decodeChunks(client.packets.subList(1, 4)));
    }

    @Test
    void canBeCanceledWhileQueued() throws Exception {
        Path path = Files.writeString(tempDir.resolve("queued.txt"), "data", StandardCharsets.UTF_8);
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        AtomicBoolean removed = new AtomicBoolean(false);
        UploadTask task = new UploadTask(
                path.toFile(),
                4,
                () -> client,
                ui,
                queuedTask -> removed.set(true),
                fileId -> {
                }
        );

        task.showQueuedWaiting();
        ui.outgoingView("queued.txt").cancelAction.run();

        assertTrue(removed.get());
        assertEquals("Removed from queue", ui.outgoingView("queued.txt").canceledReason);
    }

    private String decodeChunks(List<FilePacket> packets) {
        StringBuilder builder = new StringBuilder();
        for (FilePacket packet : packets) {
            builder.append(new String(Base64.getDecoder().decode(packet.payload()), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

}
