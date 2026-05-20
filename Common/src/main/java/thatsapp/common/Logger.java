package thatsapp.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple console logger shared by client and server.
 */
public final class Logger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Logger() {
    }

    public static void info(String message) {
        log("INFO", message, null);
    }

    public static void warn(String message) {
        log("WARN", message, null);
    }

    public static void error(String message) {
        log("ERROR", message, null);
    }

    public static void error(String message, Throwable throwable) {
        log("ERROR", message, throwable);
    }

    private static void log(String level, String message, Throwable throwable) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        System.out.printf("[%s] [%s] %s%n", timestamp, level, message);
        if (throwable != null) {
            throwable.printStackTrace(System.out);
        }
    }
}
