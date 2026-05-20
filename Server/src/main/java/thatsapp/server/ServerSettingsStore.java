package thatsapp.server;

import thatsapp.common.Logger;
import thatsapp.common.ServerRules;
import thatsapp.server.messages.ServerLogMessages;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Properties;

/**
 * Persists server-side settings (limits + port) in an obfuscated form.
 */
public final class ServerSettingsStore {

    private static final String SETTINGS_FILE_PROPERTY = "thatsapp.server.settingsFile";
    private static final byte[] MAGIC = new byte[]{'T', 'H', 'S', 'S'};
    private static final int DEFAULT_PORT = 8000;
    private static final int SALT_LENGTH = 16;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String ALGO_GCM = "AES/GCM/NoPadding";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SECRET_PHRASE = "ThatsAppServerConfigKey_v1";

    private ServerSettingsStore() {
    }

    public static void save(ServerRules rules, int port) {
        if (rules == null) {
            return;
        }
        try {
            byte[] salt = randomBytes(SALT_LENGTH);
            byte[] iv = randomBytes(GCM_IV_LENGTH);
            SecretKey secretKey = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(ALGO_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encryptedData = cipher.doFinal(serialize(rules, port));
            File file = new File(getFilePath());
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(MAGIC);
                fos.write(salt);
                fos.write(iv);
                fos.write(encryptedData);
            }
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_SAVING_SERVER_SETTINGS_PREFIX, e), e);
        }
    }

    public static ServerRules load() {
        Properties props = loadProperties();
        if (props == null) {
            return null;
        }
        try {
            int userLimit = Integer.parseInt(props.getProperty("userLimit"));
            int fileCountLimit = Integer.parseInt(props.getProperty("fileCountLimit"));
            long fileSizeBytesLimit = Long.parseLong(props.getProperty("fileSizeBytesLimit"));
            int messageCharacterLimit = Integer.parseInt(props.getProperty("messageCharacterLimit"));
            return new ServerRules(userLimit, fileCountLimit, fileSizeBytesLimit, messageCharacterLimit);
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_READING_SERVER_SETTINGS_FILE_PREFIX, e), e);
            return null;
        }
    }

    public static int loadPort() {
        Properties props = loadProperties();
        return props == null ? DEFAULT_PORT : Integer.parseInt(props.getProperty("port"));
    }

    public static String getFilePath() {
        String override = System.getProperty(SETTINGS_FILE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize().toString();
        }
        return Path.of(System.getProperty("user.home"), ".thatsapp", "server_settings.dat").toString();
    }

    private static Properties loadProperties() {
        try {
            Path path = Path.of(getFilePath());
            if (!Files.exists(path)) {
                return null;
            }
            byte[] fileContent = Files.readAllBytes(path);
            if (!isValidFormat(fileContent)) {
                Logger.warn(ServerLogMessages.SERVER_SETTINGS_FILE_UNKNOWN_FORMAT);
                return null;
            }
            return decrypt(fileContent);
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_READING_SERVER_SETTINGS_FILE_PREFIX, e), e);
            return null;
        }
    }

    private static Properties decrypt(byte[] fileContent) {
        try {
            int offset = MAGIC.length;
            byte[] salt = Arrays.copyOfRange(fileContent, offset, offset + SALT_LENGTH);
            offset += SALT_LENGTH;
            byte[] iv = Arrays.copyOfRange(fileContent, offset, offset + GCM_IV_LENGTH);
            offset += GCM_IV_LENGTH;
            byte[] encryptedData = Arrays.copyOfRange(fileContent, offset, fileContent.length);
            SecretKey secretKey = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(ALGO_GCM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] decrypted = cipher.doFinal(encryptedData);
            Properties props = new Properties();
            props.load(new ByteArrayInputStream(decrypted));
            return props;
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_DECRYPTING_SERVER_SETTINGS_PREFIX, e), e);
            return null;
        }
    }

    private static byte[] serialize(ServerRules rules, int port) {
        try {
            Properties props = new Properties();
            props.setProperty("userLimit", String.valueOf(rules.userLimit()));
            props.setProperty("fileCountLimit", String.valueOf(rules.fileCountLimit()));
            props.setProperty("fileSizeBytesLimit", String.valueOf(rules.fileSizeBytesLimit()));
            props.setProperty("messageCharacterLimit", String.valueOf(rules.messageCharacterLimit()));
            props.setProperty("port", String.valueOf(port));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            props.store(baos, null);
            return baos.toByteArray();
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_SERIALIZING_SERVER_SETTINGS_PREFIX, e), e);
            return new byte[0];
        }
    }

    private static SecretKey deriveKey(byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(SECRET_PHRASE.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    private static boolean isValidFormat(byte[] content) {
        if (content.length < MAGIC.length + SALT_LENGTH + GCM_IV_LENGTH) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (content[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
