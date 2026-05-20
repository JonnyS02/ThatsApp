package thatsapp.common;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LoggerTest {

    @Test
    void writesLevelMessageAndThrowableToStdout() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));

            Logger.info("hello");
            Logger.error("broken", new IllegalStateException("boom"));
        } finally {
            System.setOut(originalOut);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("[INFO] hello"));
        assertTrue(text.contains("[ERROR] broken"));
        assertTrue(text.contains("IllegalStateException: boom"));
    }
}
