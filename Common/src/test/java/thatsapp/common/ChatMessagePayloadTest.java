package thatsapp.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class ChatMessagePayloadTest {

    @Test
    void wrapsAndUnwrapsPlainPayload() {
        String payload = ChatMessagePayload.wrapPlain("Hello 😀");

        assertTrue(ChatMessagePayload.isPlain(payload));
        assertFalse(ChatMessagePayload.isEncrypted(payload));
        assertEquals("Hello 😀", ChatMessagePayload.unwrapPlain(payload));
        assertEquals(7, ChatMessagePayload.countPayloadCharacters(payload));
    }

    @Test
    void countsCharactersInEncryptedPayload() {
        String message = "Hi 😀";
        byte[] plaintext = ChatMessagePayload.encodeEncryptedMessage(message);
        byte[] encryptedPayload = new byte[ChatMessagePayload.ENCRYPTED_IV_BYTES + plaintext.length + ChatMessagePayload.ENCRYPTED_TAG_BYTES];
        System.arraycopy(plaintext, 0, encryptedPayload, ChatMessagePayload.ENCRYPTED_IV_BYTES, plaintext.length);
        String payload = ChatMessagePayload.wrapEncrypted(Base64.getEncoder().encodeToString(encryptedPayload));

        assertTrue(ChatMessagePayload.isEncrypted(payload));
        assertEquals(ChatMessagePayload.countCharacters(message), ChatMessagePayload.countPayloadCharacters(payload));
    }

    @Test
    void trimsStringsByCodePointLimit() {
        assertEquals("A😀", ChatMessagePayload.trimToCharacterLimit("A😀BC", 2));
        assertEquals("", ChatMessagePayload.trimToCharacterLimit("Hello", 0));
        assertEquals("", ChatMessagePayload.trimToCharacterLimit(null, 3));
    }

    @Test
    void decodesEncryptedMessageBytesRoundTrip() {
        String message = "Hi 😀";

        byte[] encoded = ChatMessagePayload.encodeEncryptedMessage(message);

        assertEquals(message, ChatMessagePayload.decodeEncryptedMessage(encoded));
    }

    @Test
    void rejectsInvalidEncryptedMessageBytes() {
        assertThrows(IllegalArgumentException.class, () -> ChatMessagePayload.decodeEncryptedMessage(new byte[5]));
        assertThrows(IllegalArgumentException.class, () -> ChatMessagePayload.countPayloadCharacters("invalid"));
        assertThrows(IllegalArgumentException.class, () -> ChatMessagePayload.wrapEncrypted(" "));
    }

    @Test
    void countsNullAndEmptyMessagesAsZero() {
        assertEquals(0, ChatMessagePayload.countCharacters(null));
        assertEquals(0, ChatMessagePayload.countCharacters(""));
    }
}
