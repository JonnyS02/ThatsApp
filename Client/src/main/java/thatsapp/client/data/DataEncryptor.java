package thatsapp.client.data;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Properties;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;

public final class DataEncryptor {

    private static final String SETTINGS_FILE_PROPERTY = "thatsapp.client.settingsFile";
    private static final byte[] MAGIC = new byte[]{'T', 'H', 'S', '1'}; // format marker
    private static final int SALT_LENGTH = 16;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String ALGO_GCM = "AES/GCM/NoPadding";
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256;

    private DataEncryptor() {
    }

    public static void saveEncrypted() {
        try {
            byte[] salt = randomBytes(SALT_LENGTH);
            byte[] iv = randomBytes(GCM_IV_LENGTH);
            SecretKey secretKey = deriveKey(salt);
            Cipher cipher = Cipher.getInstance(ALGO_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encryptedData = cipher.doFinal(serializeSettings().getBytes(StandardCharsets.UTF_8));
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
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_WHILE_SAVING_SETTINGS_PREFIX, e), e);
        }
    }

    public static Result loadEncrypted() {
        try {
            byte[] fileContent = Files.readAllBytes(Path.of(getFilePath()));
            Properties props = decryptModern(fileContent);
            if (props == null) {
                return Result.INVALID_CONTENT;
            }
            DataHolder.hostIp = readString(props, "hostIp", DataHolder.hostIp, true);
            DataHolder.port = readInt(props);
            DataHolder.desktopserverAccessKey = readString(props, "desktopserverAccessKey", DataHolder.desktopserverAccessKey, true);
            DataHolder.desktopserverRoomSecret = readString(props, "desktopserverRoomSecret", DataHolder.desktopserverRoomSecret, true);

            DataHolder.url = readString(props, "url", DataHolder.url, true);
            DataHolder.sessionName = readString(props, "sessionName", DataHolder.sessionName, true);
            DataHolder.autoCreate = readBoolean(props, "autoCreate", DataHolder.autoCreate, true);
            DataHolder.webserverAccessKey = readString(props, "webserverAccessKey", DataHolder.webserverAccessKey, true);
            DataHolder.webserverRoomSecret = readString(props, "webserverRoomSecret", DataHolder.webserverRoomSecret, true);

            DataHolder.name = readString(props, "name", DataHolder.name, true);
            DataHolder.connectionType = readString(props, "connectionType", DataHolder.connectionType, true);
            DataHolder.autoConnect = readBoolean(props, "autoConnect", DataHolder.autoConnect, true);
            DataHolder.fileDownloadPath = readString(props, "fileDownloadPath", DataHolder.fileDownloadPath, false);
            DataHolder.autoDownloadFiles = readBoolean(props, "autoDownloadFiles", DataHolder.autoDownloadFiles, false);
            DataHolder.messagePaneBackgroundImageSource = readString(props, "messagePaneBackgroundImageSource", DataHolder.messagePaneBackgroundImageSource, false);
            DataHolder.messagePaneBackgroundImageData = readString(props, "messagePaneBackgroundImageData", DataHolder.messagePaneBackgroundImageData, false);
            DataHolder.messagePaneBackgroundImageMimeType = readString(props, "messagePaneBackgroundImageMimeType", DataHolder.messagePaneBackgroundImageMimeType, false);
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_READING_SETTINGS_FILE_PREFIX, e), e);
            return Result.INVALID_CONTENT;
        }
        return Result.SUCCESS;
    }

    public static void refreshFileExists() {
        DataHolder.fileExists = new File(getFilePath()).exists();
    }

    public static void deleteFile() {
        try {
            Files.delete(Path.of(getFilePath()));
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_WHILE_DELETING_FILE_PREFIX, e), e);
        }
    }

    private static String getFilePath() {
        String override = System.getProperty(SETTINGS_FILE_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize().toString();
        }
        return Path.of(System.getProperty("user.home"), ".thatsapp", "client_settings.dat").toString();
    }

    private static String serializeSettings() {
        Properties props = new Properties();
        props.setProperty("hostIp", DataHolder.hostIp);
        props.setProperty("port", String.valueOf(DataHolder.port));
        props.setProperty("desktopserverAccessKey", DataHolder.desktopserverAccessKey);
        props.setProperty("desktopserverRoomSecret", DataHolder.desktopserverRoomSecret);

        props.setProperty("url", DataHolder.url);
        props.setProperty("sessionName", DataHolder.sessionName);
        props.setProperty("autoCreate", String.valueOf(DataHolder.autoCreate));
        props.setProperty("webserverAccessKey", DataHolder.webserverAccessKey);
        props.setProperty("webserverRoomSecret", DataHolder.webserverRoomSecret);

        props.setProperty("name", DataHolder.name);
        props.setProperty("connectionType", DataHolder.connectionType);
        props.setProperty("autoConnect", String.valueOf(DataHolder.autoConnect));
        props.setProperty("fileDownloadPath", DataHolder.fileDownloadPath);
        props.setProperty("autoDownloadFiles", String.valueOf(DataHolder.autoDownloadFiles));
        props.setProperty("messagePaneBackgroundImageSource", DataHolder.messagePaneBackgroundImageSource);
        props.setProperty("messagePaneBackgroundImageData", DataHolder.messagePaneBackgroundImageData);
        props.setProperty("messagePaneBackgroundImageMimeType", DataHolder.messagePaneBackgroundImageMimeType);
        StringBuilder data = new StringBuilder();
        props.forEach((key, value) -> data
                .append(key)
                .append("=")
                .append(escape(String.valueOf(value)))
                .append("\n"));
        return data.toString();
    }

    private static SecretKey deriveKey(byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(DataHolder.password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    private static Properties decryptModern(byte[] fileContent) {
        try {
            if (fileContent.length < MAGIC.length + SALT_LENGTH + GCM_IV_LENGTH) {
                Logger.error(ClientLogMessages.CORRUPTED_SETTINGS_FILE);
                return null;
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (fileContent[i] != MAGIC[i]) {
                    Logger.error(ClientLogMessages.UNSUPPORTED_SETTINGS_FORMAT);
                    return null;
                }
            }
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
            return loadProperties(decrypted);
        } catch (Exception e) {
            Logger.error(ClientLogMessages.DECRYPTION_FAILED_WRONG_PIN_OR_CORRUPTED_FILE, e);
            return null;
        }
    }

    private static Properties loadProperties(byte[] data) throws IOException {
        Properties props = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    /**
     * Ensure backslashes survive the roundtrip through Properties.load, which treats '\' as an escape.
     */
    private static String escape(String value) {
        return value.replace("\\", "\\\\");
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String readString(Properties props, String key, String fallback, boolean required) {
        String value = required ? requireKey(props, key) : props.getProperty(key);
        return value == null ? fallback : value;
    }

    private static int readInt(Properties props) {
        String value = requireKey(props, "port");
        return Integer.parseInt(value);
    }

    private static boolean readBoolean(Properties props, String key, boolean fallback, boolean required) {
        String value = required ? requireKey(props, key) : props.getProperty(key);
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }

    private static String requireKey(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("Missing setting: " + key);
        }
        return value;
    }
}
