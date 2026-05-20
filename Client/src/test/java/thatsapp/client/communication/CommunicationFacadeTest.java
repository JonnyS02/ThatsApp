package thatsapp.client.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.client.communication.filetransfer.api.FileTransferService;
import thatsapp.client.communication.transport.CommunicationClient;
import thatsapp.client.communication.transport.CommunicationClientListener;
import thatsapp.client.communication.transport.Config;
import thatsapp.client.data.DataHolder;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.client.test.FxTestSupport;
import thatsapp.client.ui.controllers.MainController;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

class CommunicationFacadeTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initToolkit() throws Exception {
        FxTestSupport.initToolkit();
    }

    @BeforeEach
    void resetDataHolder() {
        DataHolder.isConnected = false;
        DataHolder.isConnecting = false;
        DataHolder.connectionErrorMessage = "";
        DataHolder.closingStatus = 0;
        DataHolder.connectionType = DataHolder.connectionTypes[0];
    }

    @Test
    void listenerOnConnectedPopulatesUsersAndUpdatesUi() throws Exception {
        RecordingMainController controller = new RecordingMainController();
        RecordingFileTransferService transfers = new RecordingFileTransferService();
        CommunicationFacade facade = new CommunicationFacade(controller, transfers, false);
        Map<Integer, String> initialUsers = usersOf(facade);
        CommunicationClientListener listener = clientListenerOf(facade);
        Map<Integer, String> users = new LinkedHashMap<>();
        users.put(7, "Alex");
        users.put(8, "Alex");

        listener.onConnected(7, users, 250, 2048, false);
        FxTestSupport.drainFx();

        assertTrue(DataHolder.isConnected);
        assertFalse(DataHolder.isConnecting);
        assertTrue(controller.inputEnabled);
        assertTrue(controller.connectDanger);
        assertEquals("Disconnect", controller.connectText);
        assertFalse(controller.spinnerVisible);
        assertTrue(controller.connectionStatusConnected);
        assertEquals(250, controller.messageCharacterLimit);
        assertEquals(2048L, transfers.fileSizeBytesLimit);
        assertFalse(controller.fileTransferEnabled);
        assertFalse(transfers.fileTransferEnabled);
        assertTrue(controller.messages.stream().anyMatch(message -> message.text().equals(ClientLogMessages.connectedAs("Alex")) && message.fxml().equals("system.fxml")));

        Map<Integer, String> actualUsers = usersOf(facade);
        assertSame(initialUsers, actualUsers);
        assertEquals("Server", actualUsers.get(0));
        assertEquals("Alex", actualUsers.get(7));
        assertEquals("Alex (1)", actualUsers.get(8));
        assertTrue(controller.headlineText.contains("Alex"));
    }

    @Test
    void listenerOnConnectionFailedResetsStateAndReportsReason() throws Exception {
        RecordingMainController controller = new RecordingMainController();
        RecordingFileTransferService transfers = new RecordingFileTransferService();
        CommunicationFacade facade = new CommunicationFacade(controller, transfers, false);
        CommunicationClientListener listener = clientListenerOf(facade);
        DataHolder.isConnected = true;
        DataHolder.isConnecting = true;
        controller.spinnerVisible = true;

        listener.onConnectionFailed("Nope");

        assertFalse(DataHolder.isConnected);
        assertFalse(DataHolder.isConnecting);
        assertEquals("", DataHolder.connectionErrorMessage);
        assertFalse(controller.connectDanger);
        assertEquals("Connect", controller.connectText);
        assertTrue(controller.connectionControlsEnabled);
        assertFalse(controller.spinnerVisible);
        assertEquals(1, controller.clearMessageNodesCalls);
        assertFalse(controller.connectionStatusConnected);
        assertEquals(1, transfers.cancelAllCalls);
        assertEquals("Nope", controller.messages.getLast().text());
    }

    @Test
    void disconnectingRequestsDisconnectAndCancelsTransfers() throws Exception {
        RecordingMainController controller = new RecordingMainController();
        RecordingFileTransferService transfers = new RecordingFileTransferService();
        CommunicationFacade facade = new CommunicationFacade(controller, transfers, false);
        RecordingClient client = new RecordingClient();
        setField(facade, "client", client);
        DataHolder.isConnecting = true;

        facade.disconnecting();
        waitFor(() -> client.disconnectCalls == 1);

        assertFalse(DataHolder.isConnecting);
        assertTrue(controller.connectButtonDisabled);
        assertFalse(controller.inputEnabled);
        assertTrue(controller.spinnerVisible);
        assertTrue(controller.scrolledDown);
        assertEquals(1, transfers.cancelAllCalls);
    }

    @Test
    void disconnectedClearsTypingStateAndUsesClosingStatusMessage() throws Exception {
        RecordingMainController controller = new RecordingMainController();
        RecordingFileTransferService transfers = new RecordingFileTransferService();
        CommunicationFacade facade = new CommunicationFacade(controller, transfers, false);
        facade.setOwnId(1);
        setUsers(facade, new HashMap<>(Map.of(0, "Server", 1, "Jamie", 2, "Sam")));
        DataHolder.isConnected = true;
        DataHolder.isConnecting = true;

        facade.handleTyping(2, System.currentTimeMillis() + 5_000);
        FxTestSupport.drainFx();
        assertEquals("Sam is typing...", controller.headlineText);

        DataHolder.closingStatus = -1;
        facade.disconnected();

        assertFalse(DataHolder.isConnected);
        assertFalse(DataHolder.isConnecting);
        assertEquals(0, DataHolder.closingStatus);
        assertEquals("", controller.headlineText);
        assertFalse(controller.connectionStatusConnected);
        assertEquals(1, transfers.cancelAllCalls);
        assertEquals(ClientLogMessages.SERVER_REFUSED_ACCESS_KEY, controller.messages.getLast().text());
    }

    @Test
    void sendMethodsAndCancelConnectingDelegateToDependencies() throws Exception {
        RecordingMainController controller = new RecordingMainController();
        RecordingFileTransferService transfers = new RecordingFileTransferService();
        CommunicationFacade facade = new CommunicationFacade(controller, transfers, false);
        RecordingClient client = new RecordingClient();
        setField(facade, "client", client);
        File file = Files.writeString(tempDir.resolve("upload.txt"), "hello").toFile();

        facade.sendMessage("Hi");
        facade.sendTypingPing(100);
        DataHolder.isConnected = true;
        facade.sendTypingPing(100);
        facade.sendTypingStopped();
        facade.sendFiles(List.of(file));
        facade.cancelConnecting();

        assertEquals(List.of("Hi"), client.sentMessages);
        assertEquals(List.of(100L, 0L), client.typingTtls);
        assertEquals(List.of(file), transfers.enqueuedFiles);
        assertEquals(1, client.requestCancelCalls);
        assertEquals(ClientLogMessages.CONNECTION_CANCELED, DataHolder.connectionErrorMessage);
    }

    @Test
    void userLifecycleAndTypingHeadlineAreTracked() throws Exception {
        RecordingMainController controller = new RecordingMainController();
        RecordingFileTransferService transfers = new RecordingFileTransferService();
        CommunicationFacade facade = new CommunicationFacade(controller, transfers, false);

        facade.registerUser("Alex", 2);
        facade.registerUser("Alex", 3);
        FxTestSupport.drainFx();
        facade.updateUserLabel();
        FxTestSupport.drainFx();

        Map<Integer, String> users = usersOf(facade);
        assertEquals("Alex", users.get(2));
        assertEquals("Alex (1)", users.get(3));
        assertTrue(controller.messages.stream().anyMatch(message -> message.text().equals("Joined the chat") && message.user().equals("Alex")));
        assertTrue(controller.headlineText.contains("Alex"));

        facade.handleTyping(2, System.currentTimeMillis() + 5_000);
        FxTestSupport.drainFx();
        assertEquals("Alex is typing...", controller.headlineText);

        facade.handleTyping(3, System.currentTimeMillis() + 5_000);
        FxTestSupport.drainFx();
        assertEquals("2 users are typing...", controller.headlineText);

        facade.removeUser(2);
        FxTestSupport.drainFx();

        assertFalse(usersOf(facade).containsKey(2));
        assertTrue(controller.messages.stream().anyMatch(message -> message.text().equals("Left the chat") && message.user().equals("Alex")));
    }

    @Test
    void listenerRoutesMessagesAndFilePackets() throws Exception {
        RecordingMainController controller = new RecordingMainController();
        RecordingFileTransferService transfers = new RecordingFileTransferService();
        CommunicationFacade facade = new CommunicationFacade(controller, transfers, false);
        CommunicationClientListener listener = clientListenerOf(facade);
        FilePacket packet = new FilePacket(StatusCodes.FILE_META, "file-1", "name.txt", 4, -1, 1, "", 2, "");
        setUsers(facade, new HashMap<>(Map.of(0, "Server", 2, "Alex")));

        listener.onTextMessage(2, "Hello");
        listener.onServerMessage("Notice");
        listener.onFilePacket(packet);

        assertTrue(controller.messages.stream().anyMatch(message -> message.text().equals("Hello") && message.user().equals("Alex")));
        assertTrue(controller.messages.stream().anyMatch(message -> message.text().equals("Notice") && message.user().equals("Server")));
        assertSame(packet, transfers.lastIncomingPacket);
    }

    private static CommunicationClientListener clientListenerOf(CommunicationFacade facade) throws Exception {
        Field field = CommunicationFacade.class.getDeclaredField("clientListener");
        field.setAccessible(true);
        return (CommunicationClientListener) field.get(facade);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, String> usersOf(CommunicationFacade facade) throws Exception {
        Field field = CommunicationFacade.class.getDeclaredField("users");
        field.setAccessible(true);
        return (Map<Integer, String>) field.get(facade);
    }

    private static void setUsers(CommunicationFacade facade, Map<Integer, String> users) throws Exception {
        Field field = CommunicationFacade.class.getDeclaredField("users");
        field.setAccessible(true);
        field.set(facade, users);
    }

    private static void setField(CommunicationFacade facade, String fieldName, Object value) throws Exception {
        Field field = CommunicationFacade.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(facade, value);
    }

    private static void waitFor(Check condition) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.matches() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.matches());
    }

    @FunctionalInterface
    private interface Check {
        boolean matches();
    }

    private static final class RecordingFileTransferService implements FileTransferService {
        private long fileSizeBytesLimit;
        private boolean fileTransferEnabled;
        private int cancelAllCalls;
        private List<File> enqueuedFiles = List.of();
        private FilePacket lastIncomingPacket;

        @Override
        public void setFileSizeBytesLimit(long fileSizeBytesLimit) {
            this.fileSizeBytesLimit = fileSizeBytesLimit;
        }

        @Override
        public void setFileTransferEnabled(boolean fileTransferEnabled) {
            this.fileTransferEnabled = fileTransferEnabled;
        }

        @Override
        public void enqueueUploads(List<File> files) {
            this.enqueuedFiles = List.copyOf(files);
        }

        @Override
        public void handleIncoming(FilePacket packet) {
            this.lastIncomingPacket = packet;
        }

        @Override
        public void cancelAll() {
            cancelAllCalls++;
        }
    }

    private static final class RecordingClient implements CommunicationClient {
        private final List<String> sentMessages = new ArrayList<>();
        private final List<Long> typingTtls = new ArrayList<>();
        private int requestCancelCalls;
        private int disconnectCalls;

        @Override
        public void connect(Config config, CommunicationClientListener listener, Executor callbackExecutor) {
        }

        @Override
        public void requestCancel() {
            requestCancelCalls++;
        }

        @Override
        public void disconnect() {
            disconnectCalls++;
        }

        @Override
        public SymmetricEncryption getSymmetric() {
            return new SymmetricEncryption("", "session");
        }

        @Override
        public void sendMessage(String message) {
            sentMessages.add(message);
        }

        @Override
        public void sendTyping(long ttlMs) {
            typingTtls.add(ttlMs);
        }

        @Override
        public void sendFilePacket(FilePacket packet) throws IOException {
        }
    }

    private static final class RecordingMainController extends MainController {
        private final List<AppendedMessage> messages = new ArrayList<>();
        private boolean inputEnabled;
        private boolean connectDanger;
        private String connectText = "";
        private boolean spinnerVisible;
        private boolean connectionControlsEnabled;
        private boolean connectionStatusConnected;
        private boolean connectButtonDisabled;
        private boolean scrolledDown;
        private String headlineText = "";
        private int messageCharacterLimit = Integer.MAX_VALUE;
        private boolean fileTransferEnabled;
        private int clearMessageNodesCalls;

        @Override
        public void appendMessage(String messageText, String fxml, String username) {
            messages.add(new AppendedMessage(messageText, fxml, username));
        }

        @Override
        public void hideSpinner() {
            spinnerVisible = false;
        }

        @Override
        public void showSpinner() {
            spinnerVisible = true;
        }

        @Override
        public void setInputEnabled(boolean enabled) {
            inputEnabled = enabled;
        }

        @Override
        public void setConnectionControlsEnabled(boolean enabled) {
            connectionControlsEnabled = enabled;
        }

        @Override
        public void setConnectButtonState(boolean danger, String text) {
            connectDanger = danger;
            connectText = text;
        }

        @Override
        public void setConnectButtonDisabled(boolean disabled) {
            connectButtonDisabled = disabled;
        }

        @Override
        public void updateConnectionStatusIcon(boolean connected) {
            connectionStatusConnected = connected;
        }

        @Override
        public void clearMessageNodes() {
            clearMessageNodesCalls++;
        }

        @Override
        public void scrollDown() {
            scrolledDown = true;
        }

        @Override
        public void setHeadlineText(String text) {
            headlineText = text;
        }

        @Override
        public void setMessageCharacterLimit(int limit) {
            messageCharacterLimit = limit;
        }

        @Override
        public void setFileTransferEnabled(boolean enabled) {
            fileTransferEnabled = enabled;
        }
    }

    private record AppendedMessage(String text, String fxml, String user) {
    }
}
