package thatsapp.client.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataEncryptorTest {

    private static final String SETTINGS_FILE_PROPERTY = "thatsapp.client.settingsFile";

    @TempDir
    Path tempDir;

    private HolderState originalState;

    @BeforeEach
    void setUp() {
        originalState = HolderState.capture();
    }

    @AfterEach
    void tearDown() {
        originalState.restore();
        System.clearProperty(SETTINGS_FILE_PROPERTY);
    }

    @Test
    void savesLoadsAndDeletesEncryptedSettings() throws Exception {
        Path settingsFile = tempDir.resolve("client_settings.dat");
        System.setProperty(SETTINGS_FILE_PROPERTY, settingsFile.toString());
        DataHolder.password = "1234";
        DataHolder.hostIp = "192.168.1.10";
        DataHolder.port = 4567;
        DataHolder.desktopserverAccessKey = "desktop-key";
        DataHolder.desktopserverRoomSecret = "desktop-room-secret";
        DataHolder.url = "https://example.com/";
        DataHolder.sessionName = "session";
        DataHolder.autoCreate = false;
        DataHolder.webserverAccessKey = "web-key";
        DataHolder.webserverRoomSecret = "web-room-secret";
        DataHolder.name = "Jamie";
        DataHolder.connectionType = "Desktopserver";
        DataHolder.autoConnect = true;
        DataHolder.fileDownloadPath = "C:\\Downloads";
        DataHolder.autoDownloadFiles = true;
        DataHolder.messagePaneBackgroundImageSource = "image.png";
        DataHolder.messagePaneBackgroundImageData = "Zm9v";
        DataHolder.messagePaneBackgroundImageMimeType = "image/png";

        DataEncryptor.saveEncrypted();
        DataHolder.fileExists = false;
        DataHolder.hostIp = "changed";
        DataHolder.port = 1;
        DataHolder.url = "https://invalid/";

        assertEquals(Result.SUCCESS, DataEncryptor.loadEncrypted());
        assertTrue(Files.exists(settingsFile));
        assertEquals("192.168.1.10", DataHolder.hostIp);
        assertEquals(4567, DataHolder.port);
        assertEquals("https://example.com/", DataHolder.url);
        assertEquals("Jamie", DataHolder.name);
        assertEquals("image/png", DataHolder.messagePaneBackgroundImageMimeType);

        DataEncryptor.refreshFileExists();
        assertTrue(DataHolder.fileExists);

        String fileText = Files.readString(settingsFile, StandardCharsets.ISO_8859_1);
        assertFalse(fileText.contains("192.168.1.10"));

        DataEncryptor.deleteFile();
        assertFalse(Files.exists(settingsFile));
    }

    @Test
    void returnsInvalidContentForCorruptedFiles() throws Exception {
        Path settingsFile = tempDir.resolve("broken.dat");
        System.setProperty(SETTINGS_FILE_PROPERTY, settingsFile.toString());
        DataHolder.password = "1234";
        Files.writeString(settingsFile, "broken", StandardCharsets.UTF_8);

        assertEquals(Result.INVALID_CONTENT, DataEncryptor.loadEncrypted());
    }

    @Test
    void savesSettingsWhenOverrideIsOnlyAFileName() throws Exception {
        String fileName = "client-settings-" + System.nanoTime() + ".dat";
        Path settingsFile = Path.of(fileName).toAbsolutePath().normalize();
        System.setProperty(SETTINGS_FILE_PROPERTY, fileName);
        DataHolder.password = "1234";
        DataHolder.hostIp = "127.0.0.1";
        DataHolder.port = 9000;

        try {
            DataEncryptor.saveEncrypted();

            assertTrue(Files.exists(settingsFile));
            assertEquals(Result.SUCCESS, DataEncryptor.loadEncrypted());
            assertEquals("127.0.0.1", DataHolder.hostIp);
            assertEquals(9000, DataHolder.port);
        } finally {
            System.clearProperty(SETTINGS_FILE_PROPERTY);
            Files.deleteIfExists(settingsFile);
        }
    }

    @Test
    void usesPortableDefaultSettingsPathOutsideWindows() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        String originalOsName = System.getProperty("os.name");
        Path settingsFile = tempDir.resolve(".thatsapp").resolve("client_settings.dat");
        DataHolder.password = "1234";
        DataHolder.hostIp = "127.0.0.1";
        DataHolder.port = 9001;
        System.setProperty("user.home", tempDir.toString());
        System.setProperty("os.name", "Linux");

        try {
            DataEncryptor.saveEncrypted();

            assertTrue(Files.exists(settingsFile));
            assertEquals(Result.SUCCESS, DataEncryptor.loadEncrypted());
            assertEquals("127.0.0.1", DataHolder.hostIp);
            assertEquals(9001, DataHolder.port);
        } finally {
            restoreSystemProperty("user.home", originalUserHome);
            restoreSystemProperty("os.name", originalOsName);
            Files.deleteIfExists(settingsFile);
            Files.deleteIfExists(settingsFile.getParent());
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }

    private record HolderState(
            boolean fileExists,
            String password,
            String hostIp,
            int port,
            String desktopserverAccessKey,
            String desktopserverRoomSecret,
            String url,
            String webserverAccessKey,
            String webserverRoomSecret,
            String sessionName,
            boolean autoCreate,
            String name,
            String connectionType,
            boolean autoConnect,
            String fileDownloadPath,
            boolean autoDownloadFiles,
            String messagePaneBackgroundImageSource,
            String messagePaneBackgroundImageData,
            String messagePaneBackgroundImageMimeType
    ) {
        private static HolderState capture() {
            return new HolderState(
                    DataHolder.fileExists,
                    DataHolder.password,
                    DataHolder.hostIp,
                    DataHolder.port,
                    DataHolder.desktopserverAccessKey,
                    DataHolder.desktopserverRoomSecret,
                    DataHolder.url,
                    DataHolder.webserverAccessKey,
                    DataHolder.webserverRoomSecret,
                    DataHolder.sessionName,
                    DataHolder.autoCreate,
                    DataHolder.name,
                    DataHolder.connectionType,
                    DataHolder.autoConnect,
                    DataHolder.fileDownloadPath,
                    DataHolder.autoDownloadFiles,
                    DataHolder.messagePaneBackgroundImageSource,
                    DataHolder.messagePaneBackgroundImageData,
                    DataHolder.messagePaneBackgroundImageMimeType
            );
        }

        private void restore() {
            DataHolder.fileExists = fileExists;
            DataHolder.password = password;
            DataHolder.hostIp = hostIp;
            DataHolder.port = port;
            DataHolder.desktopserverAccessKey = desktopserverAccessKey;
            DataHolder.desktopserverRoomSecret = desktopserverRoomSecret;
            DataHolder.url = url;
            DataHolder.webserverAccessKey = webserverAccessKey;
            DataHolder.webserverRoomSecret = webserverRoomSecret;
            DataHolder.sessionName = sessionName;
            DataHolder.autoCreate = autoCreate;
            DataHolder.name = name;
            DataHolder.connectionType = connectionType;
            DataHolder.autoConnect = autoConnect;
            DataHolder.fileDownloadPath = fileDownloadPath;
            DataHolder.autoDownloadFiles = autoDownloadFiles;
            DataHolder.messagePaneBackgroundImageSource = messagePaneBackgroundImageSource;
            DataHolder.messagePaneBackgroundImageData = messagePaneBackgroundImageData;
            DataHolder.messagePaneBackgroundImageMimeType = messagePaneBackgroundImageMimeType;
        }
    }
}
