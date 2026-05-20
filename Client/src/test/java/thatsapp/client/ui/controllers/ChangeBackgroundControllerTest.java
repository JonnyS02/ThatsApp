package thatsapp.client.ui.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeBackgroundControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsExpectedMimeTypes() {
        assertEquals("image/jpeg", ChangeBackgroundController.detectMime("photo.jpg"));
        assertEquals("image/jpeg", ChangeBackgroundController.detectMime("photo.jpeg"));
        assertEquals("image/gif", ChangeBackgroundController.detectMime("photo.gif"));
        assertEquals("image/png", ChangeBackgroundController.detectMime("photo.unknown"));
    }

    @Test
    void detectsExistingFiles() throws Exception {
        Path image = Files.writeString(tempDir.resolve("image.png"), "data");

        assertTrue(ChangeBackgroundController.isExistingFile(image.toString()));
        assertFalse(ChangeBackgroundController.isExistingFile(tempDir.resolve("missing.png").toString()));
        assertFalse(ChangeBackgroundController.isExistingFile(" "));
    }
}
