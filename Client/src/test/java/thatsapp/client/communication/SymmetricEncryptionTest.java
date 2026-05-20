package thatsapp.client.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymmetricEncryptionTest {

    @TempDir
    Path tempDir;

    @Test
    void leavesPayloadsUntouchedWhenEncryptionIsDisabled() throws IOException {
        SymmetricEncryption encryption = new SymmetricEncryption("", "nonce");
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "plain", StandardCharsets.UTF_8);

        encryption.encryptFile(source, target);

        assertFalse(encryption.isEnabled());
        assertEquals("hello", encryption.encryptMessage("hello"));
        assertEquals("hello", encryption.decryptMessage("hello"));
        assertEquals("name.txt", encryption.encryptFileName("name.txt"));
        assertEquals("name.txt", encryption.decryptFileName("name.txt"));
        assertEquals("chat", encryption.decryptChatMessage(encryption.encryptChatMessage("chat")));
        assertEquals("plain", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void encryptsAndDecryptsMessagesFileNamesAndFiles() throws IOException {
        SymmetricEncryption encryption = new SymmetricEncryption("1234567890abcdef", "session");
        Path source = tempDir.resolve("source.txt");
        Path encrypted = tempDir.resolve("encrypted.bin");
        Path decrypted = tempDir.resolve("decrypted.txt");
        Files.writeString(source, "hello encrypted world", StandardCharsets.UTF_8);

        String encryptedMessage = encryption.encryptMessage("hello");
        String encryptedChatMessage = encryption.encryptChatMessage("Hi 😀");
        String encryptedFileName = encryption.encryptFileName("secret.txt");
        encryption.encryptFile(source, encrypted);
        encryption.decryptFile(encrypted, decrypted);

        assertTrue(encryption.isEnabled());
        assertNotEquals("hello", encryptedMessage);
        assertEquals("hello", encryption.decryptMessage(encryptedMessage));
        assertEquals("Hi 😀", encryption.decryptChatMessage(encryptedChatMessage));
        assertNotEquals("secret.txt", encryptedFileName);
        assertEquals("secret.txt", encryption.decryptFileName(encryptedFileName));
        assertEquals("hello encrypted world", Files.readString(decrypted, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsWrongChatPayloadMode() {
        SymmetricEncryption encryption = new SymmetricEncryption("1234567890abcdef", "session");

        assertThrows(IllegalStateException.class, () -> encryption.decryptChatMessage("P2:plain"));
    }
}
