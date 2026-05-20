package thatsapp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.common.ServerRules;

class ServerSettingsStoreTest {

    private static final String SETTINGS_FILE_PROPERTY = "thatsapp.server.settingsFile";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(SETTINGS_FILE_PROPERTY);
    }

    @Test
    void savesAndLoadsServerRulesAndPort() throws Exception {
        Path file = tempDir.resolve("server_settings.dat");
        System.setProperty(SETTINGS_FILE_PROPERTY, file.toString());
        ServerRules rules = new ServerRules(5, 20, 12L * 1024 * 1024, 600);

        ServerSettingsStore.save(rules, 8080);

        assertTrue(Files.exists(file));
        assertEquals(rules, ServerSettingsStore.load());
        assertEquals(8080, ServerSettingsStore.loadPort());
        assertFalse(Files.readString(file, StandardCharsets.ISO_8859_1).contains("8080"));
    }

    @Test
    void fallsBackForMissingOrBrokenFiles() throws Exception {
        Path file = tempDir.resolve("broken.dat");
        System.setProperty(SETTINGS_FILE_PROPERTY, file.toString());

        assertNull(ServerSettingsStore.load());
        assertEquals(8000, ServerSettingsStore.loadPort());

        Files.writeString(file, "broken", StandardCharsets.UTF_8);

        assertNull(ServerSettingsStore.load());
        assertEquals(8000, ServerSettingsStore.loadPort());
    }

    @Test
    void savesSettingsWhenOverrideIsOnlyAFileName() throws Exception {
        String fileName = "server-settings-" + System.nanoTime() + ".dat";
        Path file = Path.of(fileName).toAbsolutePath().normalize();
        System.setProperty(SETTINGS_FILE_PROPERTY, fileName);
        ServerRules rules = new ServerRules(3, 15, 2048, 120);

        try {
            ServerSettingsStore.save(rules, 8123);

            assertTrue(Files.exists(file));
            assertEquals(rules, ServerSettingsStore.load());
            assertEquals(8123, ServerSettingsStore.loadPort());
        } finally {
            System.clearProperty(SETTINGS_FILE_PROPERTY);
            Files.deleteIfExists(file);
        }
    }

    @Test
    void usesPortableDefaultSettingsPathOutsideWindows() throws Exception {
        String originalUserHome = System.getProperty("user.home");
        String originalOsName = System.getProperty("os.name");
        Path file = tempDir.resolve(".thatsapp").resolve("server_settings.dat");
        ServerRules rules = new ServerRules(4, 18, 4096, 150);
        System.setProperty("user.home", tempDir.toString());
        System.setProperty("os.name", "Linux");

        try {
            ServerSettingsStore.save(rules, 8124);

            assertTrue(Files.exists(file));
            assertEquals(rules, ServerSettingsStore.load());
            assertEquals(8124, ServerSettingsStore.loadPort());
        } finally {
            restoreSystemProperty("user.home", originalUserHome);
            restoreSystemProperty("os.name", originalOsName);
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
        }
    }

    @Test
    void loadsSavedSettingsAfterOsAndUserChange() {
        String originalUserName = System.getProperty("user.name");
        String originalOsName = System.getProperty("os.name");
        Path file = tempDir.resolve("server_settings.dat");
        System.setProperty(SETTINGS_FILE_PROPERTY, file.toString());
        ServerRules rules = new ServerRules(7, 22, 8192, 180);

        try {
            System.setProperty("user.name", "windows-user");
            System.setProperty("os.name", "Windows 11");
            ServerSettingsStore.save(rules, 9000);

            System.setProperty("user.name", "linux-user");
            System.setProperty("os.name", "Linux");

            assertEquals(rules, ServerSettingsStore.load());
            assertEquals(9000, ServerSettingsStore.loadPort());
        } finally {
            restoreSystemProperty("user.name", originalUserName);
            restoreSystemProperty("os.name", originalOsName);
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }
}
