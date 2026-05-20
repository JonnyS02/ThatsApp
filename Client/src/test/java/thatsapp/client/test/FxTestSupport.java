package thatsapp.client.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;

public final class FxTestSupport {

    private FxTestSupport() {
    }

    public static void initToolkit() throws InterruptedException {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            await(latch);
        } catch (IllegalStateException ignored) {
            // JavaFX toolkit already running.
        }
    }

    public static void drainFx() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(latch::countDown);
            await(latch);
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX operation timed out");
        }
    }
}
