package thatsapp.server.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.common.Message;
import thatsapp.common.StatusCodes;
import thatsapp.server.ui.controllers.MainController;

class ServerMessagingTest {

    private static final String SETTINGS_FILE_PROPERTY = "thatsapp.server.settingsFile";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(SETTINGS_FILE_PROPERTY);
    }

    @Test
    void broadcastsMessagesToOtherReadyClientsOnly() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingHandler sender = new RecordingHandler(1, true);
        RecordingHandler recipient = new RecordingHandler(2, true);
        RecordingHandler notReady = new RecordingHandler(3, false);
        server.putClient(sender);
        server.putClient(recipient);
        server.putClient(notReady);

        server.broadcast(new Message("hello", 1, StatusCodes.MESSAGE), 1, StatusCodes.MESSAGE);
        server.sendServerMessageToAll("system");

        assertEquals(2, recipient.messages.size());
        assertEquals("hello", recipient.messages.get(0).message());
        assertEquals(StatusCodes.MESSAGE, recipient.messages.get(0).status());
        assertEquals(StatusCodes.SERVER_MESSAGE, recipient.messages.get(1).status());
        assertEquals(1, sender.messages.size());
        assertEquals(StatusCodes.SERVER_MESSAGE, sender.messages.getFirst().status());
        assertTrue(notReady.messages.isEmpty());
    }

    @Test
    void registersAndRemovesClientsWhileOnlyUsingReadyHandlers() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"));
        RecordingHandler joiningClient = new RecordingHandler(1, true);
        RecordingHandler recipient = new RecordingHandler(2, true);
        RecordingHandler notReady = new RecordingHandler(3, false);
        server.putClient(joiningClient);
        server.putClient(recipient);
        server.putClient(notReady);

        server.registerClient(1, "Alice");

        assertEquals("Alice", server.getClientNames().get(1));
        assertEquals(1, recipient.joinedNames.size());
        assertEquals("Alice", recipient.joinedNames.getFirst());
        assertTrue(notReady.joinedNames.isEmpty());
        assertEquals(joiningClient, server.getClient(1));
        assertNull(server.getClient(3));

        server.removeClient(1);

        assertTrue(server.getClientNames().isEmpty());
        assertEquals(1, recipient.removedClientIds.size());
        assertEquals(1, recipient.removedClientIds.getFirst());
        assertTrue(notReady.removedClientIds.isEmpty());
    }

    private static final class TestServer extends Server {
        private TestServer(Path settingsFile) {
            super(prepare(settingsFile));
        }

        private void putClient(RecordingHandler handler) throws Exception {
            Field field = Server.class.getDeclaredField("clients");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Integer, ClientHandler> clients = (ConcurrentHashMap<Integer, ClientHandler>) field.get(this);
            clients.put(handler.clientId(), handler);
        }

        private static MainController prepare(Path settingsFile) {
            System.setProperty(SETTINGS_FILE_PROPERTY, settingsFile.toString());
            return null;
        }
    }

    private static final class RecordingHandler extends ClientHandler {
        private final int id;
        private final boolean ready;
        private final java.util.List<Message> messages = new java.util.ArrayList<>();
        private final java.util.List<String> joinedNames = new java.util.ArrayList<>();
        private final java.util.List<Integer> removedClientIds = new java.util.ArrayList<>();

        private RecordingHandler(int id, boolean ready) {
            super(null, id, null, "");
            this.id = id;
            this.ready = ready;
        }

        private int clientId() {
            return id;
        }

        @Override
        public void sendMessage(Message message) {
            messages.add(message);
        }

        @Override
        public void sendClientName(String clientName, int clientId) {
            joinedNames.add(clientName);
        }

        @Override
        public void removeClientName(int clientId) {
            removedClientIds.add(clientId);
        }

        @Override
        public boolean isReady() {
            return ready;
        }
    }
}
