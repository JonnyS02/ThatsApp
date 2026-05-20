package thatsapp.client.communication;

import thatsapp.common.ChatMessagePayload;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class SymmetricEncryption {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BIT = 128;
    private static final byte[] FILE_MAGIC = new byte[]{'T', 'H', 'S', 'F'};
    private static final String ALGO = "AES/GCM/NoPadding";
    private final SecretKey sessionKey;
    private final boolean encryptionEnabled;

    public SymmetricEncryption(String roomSecret, String sessionNonce) {
        this.encryptionEnabled = roomSecret != null && !roomSecret.isBlank();
        this.sessionKey = encryptionEnabled ? deriveFinalKey(roomSecret, sessionNonce) : null;
    }

    public boolean isEnabled() {
        return encryptionEnabled;
    }

    public String encryptMessage(String message) {
        return encryptPayload(message, Base64.getEncoder());
    }

    public String decryptMessage(String encryptedMessage) {
        return decryptPayload(encryptedMessage, Base64.getDecoder());
    }

    public String encryptChatMessage(String message) {
        if (!encryptionEnabled) {
            return ChatMessagePayload.wrapPlain(message);
        }
        return ChatMessagePayload.wrapEncrypted(encryptBytes(ChatMessagePayload.encodeEncryptedMessage(message), Base64.getEncoder()));
    }

    public String decryptChatMessage(String payload) {
        if (!encryptionEnabled) {
            if (!ChatMessagePayload.isPlain(payload)) {
                throw new IllegalStateException("Invalid chat message payload");
            }
            return ChatMessagePayload.unwrapPlain(payload);
        }
        if (!ChatMessagePayload.isEncrypted(payload)) {
            throw new IllegalStateException("Invalid chat message payload");
        }
        return ChatMessagePayload.decodeEncryptedMessage(decryptBytes(ChatMessagePayload.unwrapEncrypted(payload), Base64.getDecoder()));
    }

    public String encryptFileName(String fileName) {
        return encryptPayload(fileName, Base64.getUrlEncoder().withoutPadding());
    }

    public String decryptFileName(String encryptedName) {
        return decryptPayload(encryptedName, Base64.getUrlDecoder());
    }

    public void encryptFile(Path source, Path target) throws IOException {
        if (!encryptionEnabled) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher aesCipher;
        try {
            aesCipher = Cipher.getInstance(ALGO);
            aesCipher.init(Cipher.ENCRYPT_MODE, sessionKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
        } catch (Exception e) {
            throw new IOException("Unable to init file cipher", e);
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(FILE_MAGIC);
            out.write(iv);
            try (CipherOutputStream cos = new CipherOutputStream(out, aesCipher);
                 InputStream in = Files.newInputStream(source)) {
                in.transferTo(cos);
            }
        }
    }

    public void decryptFile(Path source, Path target) throws IOException {
        if (!encryptionEnabled) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        byte[] header = new byte[FILE_MAGIC.length];
        byte[] iv = new byte[IV_LENGTH];
        try (InputStream in = Files.newInputStream(source)) {
            if (in.readNBytes(header, 0, header.length) != header.length || !Arrays.equals(header, FILE_MAGIC)) {
                throw new IOException("Invalid file header");
            }
            if (in.readNBytes(iv, 0, iv.length) != iv.length) {
                throw new IOException("Invalid file header");
            }
            Cipher aesCipher = Cipher.getInstance(ALGO);
            aesCipher.init(Cipher.DECRYPT_MODE, sessionKey, new GCMParameterSpec(TAG_LENGTH_BIT, iv));
            try (CipherInputStream cis = new CipherInputStream(in, aesCipher);
                 OutputStream out = Files.newOutputStream(target)) {
                cis.transferTo(out);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Unable to decrypt file", e);
        }
    }

    private SecretKey deriveFinalKey(String roomSecret, String sessionNonce) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(roomSecret.getBytes(StandardCharsets.UTF_8));
            digest.update(sessionNonce.getBytes(StandardCharsets.UTF_8));
            byte[] finalKey = digest.digest();
            return new SecretKeySpec(finalKey, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive symmetric key", e);
        }
    }

    private String encryptPayload(String message, Base64.Encoder encoder) {
        if (!encryptionEnabled) {
            return message;
        }
        return encryptBytes(message.getBytes(StandardCharsets.UTF_8), encoder);
    }

    private String encryptBytes(byte[] payload, Base64.Encoder encoder) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            Cipher aesCipher = Cipher.getInstance(ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            aesCipher.init(Cipher.ENCRYPT_MODE, sessionKey, spec);
            byte[] encryptedMessage = aesCipher.doFinal(payload);
            byte[] combinedIvAndEncryptedMessage = new byte[IV_LENGTH + encryptedMessage.length];
            System.arraycopy(iv, 0, combinedIvAndEncryptedMessage, 0, IV_LENGTH);
            System.arraycopy(encryptedMessage, 0, combinedIvAndEncryptedMessage, IV_LENGTH, encryptedMessage.length);
            return encoder.encodeToString(combinedIvAndEncryptedMessage);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt payload", e);
        }
    }

    private String decryptPayload(String encryptedMessage, Base64.Decoder decoder) {
        if (!encryptionEnabled) {
            return encryptedMessage;
        }
        return new String(decryptBytes(encryptedMessage, decoder), StandardCharsets.UTF_8);
    }

    private byte[] decryptBytes(String encryptedMessage, Base64.Decoder decoder) {
        try {
            byte[] decodedMessage = decoder.decode(encryptedMessage);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(decodedMessage, 0, iv, 0, IV_LENGTH);
            byte[] encryptedBytes = new byte[decodedMessage.length - IV_LENGTH];
            System.arraycopy(decodedMessage, IV_LENGTH, encryptedBytes, 0, encryptedBytes.length);
            Cipher aesCipher = Cipher.getInstance(ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            aesCipher.init(Cipher.DECRYPT_MODE, sessionKey, spec);
            return aesCipher.doFinal(encryptedBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to decrypt payload", e);
        }
    }
}
