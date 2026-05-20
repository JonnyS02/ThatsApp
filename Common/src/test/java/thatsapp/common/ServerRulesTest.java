package thatsapp.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerRulesTest {

    @Test
    void returnsExpectedDefaults() {
        ServerRules defaults = ServerRules.defaults();

        assertEquals(ServerRules.DEFAULT_USER_LIMIT, defaults.userLimit());
        assertEquals(ServerRules.DEFAULT_FILE_COUNT_LIMIT, defaults.fileCountLimit());
        assertEquals(ServerRules.DEFAULT_FILE_SIZE_BYTES_LIMIT, defaults.fileSizeBytesLimit());
        assertEquals(ServerRules.DEFAULT_MESSAGE_CHARACTER_LIMIT, defaults.messageCharacterLimit());
        assertEquals(100, defaults.fileSizeBytesLimitInMb());
    }
}
