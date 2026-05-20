package thatsapp.common;

import java.io.Serializable;

/**
 * Immutable rule set that captures the desktop server limits.
 */
public record ServerRules(int userLimit, int fileCountLimit, long fileSizeBytesLimit, int messageCharacterLimit) implements Serializable {

    public static final int DEFAULT_USER_LIMIT = 10;
    public static final int DEFAULT_FILE_COUNT_LIMIT = 100;
    public static final long DEFAULT_FILE_SIZE_BYTES_LIMIT = 100L * 1024 * 1024; // 100 MiB
    public static final int DEFAULT_MESSAGE_CHARACTER_LIMIT = 500;

    public static ServerRules defaults() {
        return new ServerRules(DEFAULT_USER_LIMIT, DEFAULT_FILE_COUNT_LIMIT, DEFAULT_FILE_SIZE_BYTES_LIMIT, DEFAULT_MESSAGE_CHARACTER_LIMIT);
    }

    public long fileSizeBytesLimitInMb() {
        return fileSizeBytesLimit / (1024 * 1024);
    }
}
