package thatsapp.server.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.IllegalStateException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.common.CryptoUtils;
import thatsapp.common.Message;
import thatsapp.common.ServerRules;
import thatsapp.common.StatusCodes;
import thatsapp.server.messages.ServerLogMessages;
import thatsapp.server.ui.controllers.MainController;

class ServerTest {

    private static final String SETTINGS_FILE_PROPERTY = "thatsapp.server.settingsFile";
    private static final String PRE_AUTH_SOCKET_TIMEOUT_PROPERTY = "thatsapp.server.preAuthSocketTimeoutMs";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initToolkit() throws Exception {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (IllegalStateException ignored) {
            // JavaFX toolkit already running.
        }
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SETTINGS_FILE_PROPERTY);
        System.clearProperty(PRE_AUTH_SOCKET_TIMEOUT_PROPERTY);
    }

    @Test
    void keepsSessionNonceStableAndAdmitsClientsUpToConfiguredLimit() {
        System.setProperty(SETTINGS_FILE_PROPERTY, tempDir.resolve("missing.dat").toString());
        Server server = new Server(null);
        server.updateRules(new ServerRules(1, -1, 1024, 200));

        String nonce = server.ensureSessionNonce("custom");

        assertTrue(server.tryAdmitClient(1));
        assertFalse(server.tryAdmitClient(2));
        assertFalse(server.isFileTransferEnabled());
        assertTrue(server.ensureChatEncrypted(true));
        assertFalse(server.ensureChatEncrypted(false));
        assertTrue(server.isChatEncrypted());
        assertEquals(server.ensureSessionNonce("ignored"), nonce);

        server.dropClientSilently(1);
        server.updateRules(new ServerRules(0, 10, 1024, 200));

        assertTrue(server.tryAdmitClient(2));
        assertTrue(server.tryAdmitClient(3));
        assertNotEquals("", server.generateChallenge());
    }

    @Test
    void startsAndStopsServerSocketLifecycle() throws Exception {
        System.setProperty(SETTINGS_FILE_PROPERTY, tempDir.resolve("settings.dat").toString());
        Server server = new Server(null);

        server.start(0, "secret");

        assertTrue(server.isRunning());
        ServerSocket socket = serverSocketOf(server);
        assertNotNull(socket);
        assertTrue(socket.getLocalPort() > 0);

        server.ensureSessionNonce("nonce");
        server.ensureChatEncrypted(true);
        server.stop();

        assertFalse(server.isRunning());
        assertEquals("", server.getSessionNonce());
        assertFalse(server.isChatEncrypted());
    }

    @Test
    void reportsStartFailureWhenPortIsAlreadyInUse() throws Exception {
        System.setProperty(SETTINGS_FILE_PROPERTY, tempDir.resolve("settings.dat").toString());
        try (ServerSocket occupied = new ServerSocket(0)) {
            RecordingController controller = new RecordingController();
            Server server = new Server(controller);

            server.start(occupied.getLocalPort(), "secret");
            drainFx();

            assertFalse(server.isRunning());
            assertTrue(controller.lastMessage.startsWith(ServerLogMessages.ERROR_STARTING_SERVER_PREFIX));
            assertEquals(0, controller.startedCalls);
        }
    }

    @Test
    void closesIdleConnectionsBeforeHandshakeCompletes() throws Exception {
        System.setProperty(SETTINGS_FILE_PROPERTY, tempDir.resolve("settings.dat").toString());
        System.setProperty(PRE_AUTH_SOCKET_TIMEOUT_PROPERTY, "200");
        Server server = new Server(null);

        server.start(0, "secret");

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), serverSocketOf(server).getLocalPort())) {
            socket.setSoTimeout(2_000);
            InputStream input = socket.getInputStream();

            byte[] header = input.readNBytes(4);
            assertEquals(4, header.length);

            Thread.sleep(400);

            assertEquals(-1, input.read());
        } finally {
            server.stop();
        }
    }

    @Test
    void keepsRegisteredClientsConnectedAfterSetupTimeoutWindow() throws Exception {
        System.setProperty(SETTINGS_FILE_PROPERTY, tempDir.resolve("settings.dat").toString());
        System.setProperty(PRE_AUTH_SOCKET_TIMEOUT_PROPERTY, "200");
        Server server = new Server(null);

        server.start(0, "secret");

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), serverSocketOf(server).getLocalPort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            socket.setSoTimeout(2_000);

            Message challenge = (Message) in.readObject();
            String[] challengeParts = challenge.message().split("\\|", -1);
            String clientNonce = "client-nonce";
            String proposedNonce = "proposed-nonce";
            String proof = CryptoUtils.hmacSha256Base64("secret", "client|" + challengeParts[0] + "|" + clientNonce);

            out.writeObject(new Message(proof + "|" + proposedNonce + "|" + clientNonce + "|false", 0, StatusCodes.HANDSHAKE_RESPONSE));
            out.flush();

            Message handshakeResult = (Message) in.readObject();
            assertEquals(StatusCodes.HANDSHAKE_OK, handshakeResult.status());

            out.writeObject(new Message("Alice", 0, StatusCodes.REGISTER));
            out.flush();

            assertEquals(1, in.readObject());
            in.readObject();
            in.readObject();
            assertTrue(awaitClientReady(server, 1));

            Thread.sleep(400);

            assertTrue(awaitClientReady(server, 1));
            server.stop();
        } finally {
            if (server.isRunning()) {
                server.stop();
            }
        }
    }

    private static ServerSocket serverSocketOf(Server server) throws Exception {
        Field field = Server.class.getDeclaredField("serverSocket");
        field.setAccessible(true);
        return (ServerSocket) field.get(server);
    }

    private static boolean awaitClientReady(Server server, int clientId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (server.getClient(clientId) != null) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static void drainFx() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(2, TimeUnit.SECONDS));
    }

    private static final class RecordingController extends MainController {
        private String lastMessage = "";
        private int startedCalls;

        @Override
        public void appendMessage(String message) {
            lastMessage = message;
        }

        @Override
        public void started() {
            startedCalls++;
        }
    }
}
