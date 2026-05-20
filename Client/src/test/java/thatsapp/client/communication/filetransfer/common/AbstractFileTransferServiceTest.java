package thatsapp.client.communication.filetransfer.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.client.communication.filetransfer.FileTransferTestSupport.RecordingUi;
import thatsapp.client.communication.filetransfer.api.FileTransferUi;
import thatsapp.common.FilePacket;

class AbstractFileTransferServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void formatsProgressAndMegabytes() {
        assertEquals(2, AbstractFileTransferService.toMb(2L * 1024 * 1024));
        assertEquals("50% (1 / 2 MB)", AbstractFileTransferService.formatProgress(1024L * 1024, 2L * 1024 * 1024));
    }

    @Test
    void filtersFilesOverConfiguredLimit() throws Exception {
        RecordingUi ui = new RecordingUi(tempDir, false);
        TestService service = new TestService(ui, 5);
        File valid = createFile("valid.txt", 4);
        File invalid = createFile("invalid.txt", 6);

        List<File> files = service.filterFiles(List.of(valid, invalid));

        assertEquals(List.of(valid), files);
        assertEquals("File exceeds limit of 0 MB.", ui.lastError);
    }

    @Test
    void rejectsSelectionsThatAreTooLarge() throws Exception {
        RecordingUi ui = new RecordingUi(tempDir, false);
        TestService service = new TestService(ui, 100);

        List<File> files = Collections.nCopies(AbstractFileTransferService.MAX_FILES + 1, createFile("a.txt", 1));

        assertTrue(service.selectionInvalid(files));
        assertEquals("At most 10 files can be selected at once.", ui.lastError);
    }

    @Test
    void buildsSafeUniqueTargetPaths() throws Exception {
        RecordingUi ui = new RecordingUi(tempDir, false);
        TestService service = new TestService(ui, 100);
        Files.writeString(tempDir.resolve("report.txt"), "existing");

        Path resolved = service.targetPath("../report.txt");

        assertEquals(tempDir.resolve("report (1).txt"), resolved);
    }

    @Test
    void handlesMissingOrUnsafeNames() {
        RecordingUi ui = new RecordingUi(tempDir, false);
        TestService service = new TestService(ui, 100);

        assertEquals(tempDir.resolve("download"), service.targetPath(null));
        assertEquals(tempDir.resolve("download"), service.targetPath(".."));
        assertFalse(service.exceeds(5));
    }

    private File createFile(String name, int size) throws Exception {
        Path path = tempDir.resolve(name);
        Files.write(path, new byte[size]);
        return path.toFile();
    }

    private static final class TestService extends AbstractFileTransferService {

        private TestService(FileTransferUi ui, long fileSizeBytesLimit) {
            super(ui, fileSizeBytesLimit);
        }

        private List<File> filterFiles(List<File> files) {
            return filterUploadFiles(files);
        }

        private boolean selectionInvalid(List<File> files) {
            return validateSelection(files);
        }

        private Path targetPath(String fileName) {
            return buildTargetPath(fileName);
        }

        private boolean exceeds(long fileSize) {
            return exceedsFileLimit(fileSize);
        }

        @Override
        public void enqueueUploads(List<File> files) {
        }

        @Override
        public void handleIncoming(FilePacket packet) {
        }

        @Override
        public void cancelAll() {
        }
    }

}
