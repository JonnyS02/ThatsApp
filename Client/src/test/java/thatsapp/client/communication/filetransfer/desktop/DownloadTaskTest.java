package thatsapp.client.communication.filetransfer.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingClient;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingUi;
import thatsapp.common.StatusCodes;

class DownloadTaskTest {

    @TempDir
    Path tempDir;

    @Test
    void writesChunksAndAcknowledgesCompletion() throws Exception {
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        CountDownLatch finished = new CountDownLatch(1);
        DownloadTask task = new DownloadTask(
                "file-1",
                "hello.txt",
                "hello.txt",
                11,
                2,
                "Alex",
                null,
                () -> client,
                ui,
                name -> tempDir.resolve(name),
                (fileId, advanceQueue) -> {
                }
        );

        task.enqueueChunk(encode("Hello "), 0, 2);
        task.enqueueChunk(encode("World"), 1, 2);
        task.enqueueFinish(finished::countDown);

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertTrue(ui.incomingView("hello.txt").finished);
        assertEquals("Hello World", Files.readString(tempDir.resolve("hello.txt"), StandardCharsets.UTF_8));
        assertEquals(StatusCodes.FILE_ACK, client.packets.getLast().status());
    }

    @Test
    void cancelsOnInvalidChunkOrder() throws Exception {
        RecordingClient client = new RecordingClient(new SymmetricEncryption("", "session"));
        RecordingUi ui = new RecordingUi(tempDir, false);
        CountDownLatch canceled = new CountDownLatch(1);
        AtomicReference<Boolean> advanceQueue = new AtomicReference<>();
        DownloadTask task = new DownloadTask(
                "file-2",
                "broken.txt",
                "broken.txt",
                4,
                1,
                "Alex",
                null,
                () -> client,
                ui,
                name -> tempDir.resolve(name),
                (fileId, advance) -> {
                    advanceQueue.set(advance);
                    canceled.countDown();
                }
        );

        task.enqueueChunk(encode("data"), 1, 1);

        assertTrue(canceled.await(2, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, advanceQueue.get());
        assertTrue(ui.incomingView("broken.txt").canceledReason.contains("Invalid chunk order"));
        assertEquals(StatusCodes.FILE_CANCEL, client.packets.getFirst().status());
        assertFalse(Files.exists(tempDir.resolve("broken.txt")));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

}
