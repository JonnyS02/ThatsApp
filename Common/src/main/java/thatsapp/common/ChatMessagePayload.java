package thatsapp.common;

import java.nio.charset.Charset;
import java.util.Base64;

public final class ChatMessagePayload {

    public static final String PLAIN_PREFIX = "P2:";
    public static final String ENCRYPTED_PREFIX = "E2:";
    public static final int ENCRYPTED_IV_BYTES = 12;
    public static final int ENCRYPTED_TAG_BYTES = 16;
    public static final int CHARACTER_BYTES = 4;
    private static final Charset MESSAGE_CHARSET = Charset.forName("UTF-32BE");

    private ChatMessagePayload() {
    }

    public static String wrapPlain(String message) {
        requireMessage(message);
        return PLAIN_PREFIX + message;
    }

    public static String wrapEncrypted(String encodedPayload) {
        if (encodedPayload == null || encodedPayload.isBlank()) {
            throw new IllegalArgumentException("Missing encrypted chat message payload.");
        }
        return ENCRYPTED_PREFIX + encodedPayload;
    }

    public static boolean isPlain(String payload) {
        return payload != null && payload.startsWith(PLAIN_PREFIX);
    }

    public static boolean isEncrypted(String payload) {
        return payload != null && payload.startsWith(ENCRYPTED_PREFIX);
    }

    public static String unwrapPlain(String payload) {
        if (!isPlain(payload)) {
            throw new IllegalArgumentException("Invalid plain chat message payload.");
        }
        return payload.substring(PLAIN_PREFIX.length());
    }

    public static String unwrapEncrypted(String payload) {
        if (!isEncrypted(payload)) {
            throw new IllegalArgumentException("Invalid encrypted chat message payload.");
        }
        return payload.substring(ENCRYPTED_PREFIX.length());
    }

    public static byte[] encodeEncryptedMessage(String message) {
        requireMessage(message);
        return message.getBytes(MESSAGE_CHARSET);
    }

    public static String decodeEncryptedMessage(byte[] bytes) {
        if (bytes == null || bytes.length % CHARACTER_BYTES != 0) {
            throw new IllegalArgumentException("Invalid encrypted chat message bytes.");
        }
        return new String(bytes, MESSAGE_CHARSET);
    }

    public static int countCharacters(String message) {
        if (message == null || message.isEmpty()) {
            return 0;
        }
        return message.codePointCount(0, message.length());
    }

    public static int countPayloadCharacters(String payload) {
        if (isPlain(payload)) {
            return countCharacters(unwrapPlain(payload));
        }
        if (isEncrypted(payload)) {
            return countEncryptedPayloadCharacters(payload);
        }
        throw new IllegalArgumentException("Invalid chat message payload.");
    }

    public static String trimToCharacterLimit(String message, int limit) {
        if (message == null) {
            return "";
        }
        if (limit <= 0) {
            return "";
        }
        if (countCharacters(message) <= limit) {
            return message;
        }
        int end = message.offsetByCodePoints(0, limit);
        return message.substring(0, end);
    }

    private static int countEncryptedPayloadCharacters(String payload) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(unwrapEncrypted(payload));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid encrypted chat message payload.", e);
        }
        int plaintextBytes = decoded.length - ENCRYPTED_IV_BYTES - ENCRYPTED_TAG_BYTES;
        if (plaintextBytes < 0 || plaintextBytes % CHARACTER_BYTES != 0) {
            throw new IllegalArgumentException("Invalid encrypted chat message payload.");
        }
        return plaintextBytes / CHARACTER_BYTES;
    }

    private static void requireMessage(String message) {
        if (message == null) {
            throw new IllegalArgumentException("Missing chat message.");
        }
    }
}
