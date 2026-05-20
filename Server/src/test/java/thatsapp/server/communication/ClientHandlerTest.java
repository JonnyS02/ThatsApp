package thatsapp.server.communication;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputFilter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thatsapp.common.ChatMessagePayload;
import thatsapp.common.CryptoUtils;
import thatsapp.common.FilePacket;
import thatsapp.common.Message;
import thatsapp.common.ServerRules;
import thatsapp.common.StatusCodes;
import thatsapp.server.ui.controllers.MainController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientHandlerTest {

    private static final String SETTINGS_FILE_PROPERTY = "thatsapp.server.settingsFile";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(SETTINGS_FILE_PROPERTY);
    }

    @Test
    void validatesChatMessagesAgainstModeAndLimits() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"), new ServerRules(10, 10, 1024, 3), false);
        ClientHandler handler = new ClientHandler(null, 1, server, "secret");

        assertNull(invoke(handler, "validateChatMessage", "P2:Hi"));
        assertEquals("Message exceeds limit of 3 characters.", invoke(handler, "validateChatMessage", "P2:Hello"));
        server.rules = new ServerRules(10, 10, 1024, 0);
        assertNull(invoke(handler, "validateChatMessage", "P2:Hello"));

        server.setChatEncrypted(true);
        assertEquals("Invalid message payload", invoke(handler, "validateChatMessage", "P2:Hi"));
        assertNull(invoke(handler, "validateChatMessage", ChatMessagePayload.wrapEncrypted(java.util.Base64.getEncoder().encodeToString(new byte[28]))));
    }

    @Test
    void inputFilterAllowsOnlyExpectedTypes() throws Exception {
        Method method = ClientHandler.class.getDeclaredMethod("createInputFilter");
        method.setAccessible(true);
        ObjectInputFilter filter = (ObjectInputFilter) method.invoke(null);

        assertSame(ObjectInputFilter.Status.ALLOWED, filter.checkInput(new TestFilterInfo(Message.class, 1, 1, 10)));
        assertSame(ObjectInputFilter.Status.ALLOWED, filter.checkInput(new TestFilterInfo(FilePacket.class, 1, 1, 10)));
        assertSame(ObjectInputFilter.Status.ALLOWED, filter.checkInput(new TestFilterInfo(FilePacket.class, 1, 500_000, 500_000_000)));
        assertSame(ObjectInputFilter.Status.REJECTED, filter.checkInput(new TestFilterInfo(Object.class, 1, 1, 10)));
        assertSame(ObjectInputFilter.Status.REJECTED, filter.checkInput(new TestFilterInfo(null, 11, 1, 10)));
    }

    @Test
    void performsHandshakeWithValidProofAndReturnsServerProof() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"), ServerRules.defaults(), false);
        ClientHandler handler = new ClientHandler(null, 1, server, "secret");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String clientNonce = "client-nonce";
        String proposedNonce = "proposed-nonce";
        String proof = CryptoUtils.hmacSha256Base64("secret", "client|challenge|" + clientNonce);
        Message response = new Message(proof + "|" + proposedNonce + "|" + clientNonce + "|false", 0, StatusCodes.HANDSHAKE_RESPONSE);

        setField(handler, "out", new ObjectOutputStream(output));
        setField(handler, "in", new ObjectInputStream(new ByteArrayInputStream(serialize(response))));

        assertEquals(true, invoke(handler, "performHandshake"));

        List<Message> messages = readMessages(output.toByteArray());
        assertEquals(2, messages.size());
        assertEquals(StatusCodes.HANDSHAKE_CHALLENGE, messages.get(0).status());
        assertEquals("challenge|", messages.get(0).message());
        assertEquals(StatusCodes.HANDSHAKE_OK, messages.get(1).status());
        assertEquals(proposedNonce, server.getSessionNonce());

        String[] parts = messages.get(1).message().split("\\|", 2);
        assertEquals(2, parts.length);
        assertEquals(proposedNonce, parts[0]);
        assertTrue(CryptoUtils.matchesBase64HmacSha256("secret", "server|challenge|" + clientNonce + "|" + proposedNonce, parts[1]));
    }

    @Test
    void rejectsHandshakeWithUnexpectedResponseStatus() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"), ServerRules.defaults(), false);
        ClientHandler handler = new ClientHandler(null, 1, server, "secret");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        setField(handler, "out", new ObjectOutputStream(output));
        setField(handler, "in", new ObjectInputStream(new ByteArrayInputStream(serialize(new Message("ignored", 0, StatusCodes.MESSAGE)))));

        assertEquals(false, invoke(handler, "performHandshake"));

        List<Message> messages = readMessages(output.toByteArray());
        assertEquals(2, messages.size());
        assertEquals(StatusCodes.HANDSHAKE_FAIL, messages.get(1).status());
        assertEquals("Invalid handshake response", messages.get(1).message());
    }

    @Test
    void rejectsHandshakeWhenChatEncryptionModeDoesNotMatch() throws Exception {
        TestServer server = new TestServer(tempDir.resolve("settings.dat"), ServerRules.defaults(), true);
        ClientHandler handler = new ClientHandler(null, 1, server, "secret");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String clientNonce = "client-nonce";
        String proposedNonce = "proposed-nonce";
        String proof = CryptoUtils.hmacSha256Base64("secret", "client|challenge|" + clientNonce);
        Message response = new Message(proof + "|" + proposedNonce + "|" + clientNonce + "|false", 0, StatusCodes.HANDSHAKE_RESPONSE);

        setField(handler, "out", new ObjectOutputStream(output));
        setField(handler, "in", new ObjectInputStream(new ByteArrayInputStream(serialize(response))));

        assertEquals(false, invoke(handler, "performHandshake"));

        List<Message> messages = readMessages(output.toByteArray());
        assertEquals(2, messages.size());
        assertEquals(StatusCodes.HANDSHAKE_FAIL, messages.get(1).status());
        assertEquals("Chat encryption mode mismatch", messages.get(1).message());
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, String methodName, Object... args) throws Exception {
        Class<?>[] parameterTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            parameterTypes[i] = switch (args[i]) {
                case Integer ignored -> int.class;
                case Boolean ignored -> boolean.class;
                default -> args[i].getClass();
            };
        }
        Method method = ClientHandler.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return (T) method.invoke(target, args);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = ClientHandler.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static byte[] serialize(Object... values) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ObjectOutputStream stream = new ObjectOutputStream(output)) {
            for (Object value : values) {
                stream.writeObject(value);
            }
        }
        return output.toByteArray();
    }

    private static List<Message> readMessages(byte[] payload) throws Exception {
        List<Message> messages = new ArrayList<>();
        try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            while (true) {
                messages.add((Message) stream.readObject());
            }
        } catch (EOFException ignored) {
            return messages;
        }
    }

    private static final class TestServer extends Server {
        private ServerRules rules;
        private boolean chatEncrypted;

        private TestServer(Path settingsFile, ServerRules rules, boolean chatEncrypted) {
            super(prepare(settingsFile));
            this.rules = rules;
            this.chatEncrypted = chatEncrypted;
        }

        @Override
        public synchronized ServerRules getRules() {
            return rules;
        }

        @Override
        public synchronized boolean isChatEncrypted() {
            return chatEncrypted;
        }

        @Override
        public synchronized boolean ensureChatEncrypted(boolean proposedMode) {
            return chatEncrypted == proposedMode;
        }

        @Override
        public String generateChallenge() {
            return "challenge";
        }

        private void setChatEncrypted(boolean chatEncrypted) {
            this.chatEncrypted = chatEncrypted;
        }

        private static MainController prepare(Path settingsFile) {
            System.setProperty(SETTINGS_FILE_PROPERTY, settingsFile.toString());
            return null;
        }
    }

    private record TestFilterInfo(Class<?> serialClass, long depth, long references, long streamBytes) implements ObjectInputFilter.FilterInfo {
        @Override
        public Class<?> serialClass() {
            return serialClass;
        }

        @Override
        public long arrayLength() {
            return -1;
        }

        @Override
        public long depth() {
            return depth;
        }

        @Override
        public long references() {
            return references;
        }

        @Override
        public long streamBytes() {
            return streamBytes;
        }
    }
}
