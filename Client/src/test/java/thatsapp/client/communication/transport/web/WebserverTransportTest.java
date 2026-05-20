package thatsapp.client.communication.transport.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.transport.CommunicationClientListener;
import thatsapp.client.communication.transport.Config;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

class WebserverTransportTest {

    private static final String ROOM_SECRET = "room-secret";
    private static final String ACCESS_KEY = "access-key";
    private static final String SESSION_NONCE = "session-nonce";

    @Test
    void connectProcessesEventsAndAuthorizedPosts() throws Exception {
        SymmetricEncryption symmetric = new SymmetricEncryption(ROOM_SECRET, SESSION_NONCE);
        try (TestWebserver server = TestWebserver.transport(
                "sessionNonce=" + urlEncode(SESSION_NONCE),
                List.of(
                        "token=token-123",
                        "clientId=7",
                        "messageCharacterLimit=250",
                        "fileSizeBytesLimit=4096",
                        "fileTransferEnabled=true",
                        "user.7=" + urlEncode(symmetric.encryptMessage("Me")),
                        "user.8=" + urlEncode(symmetric.encryptMessage("Alex"))
                ),
                sseEvent("2", StatusCodes.MESSAGE, "senderId=8&message=" + urlEncode(symmetric.encryptChatMessage("Hello")))
                        + sseEvent("3", StatusCodes.USER_JOIN, "senderId=9&message=" + urlEncode(symmetric.encryptMessage("Chris")))
                        + sseEvent("4", StatusCodes.USER_TYPING, "senderId=8&message=" + urlEncode(symmetric.encryptMessage("3000")))
                        + sseEvent("5", StatusCodes.FILE_META, "senderId=8&filePublicId=file-1&fileName=" + urlEncode("name.txt")
                        + "&fileSize=4&downloadPath=" + urlEncode("/download/file-1"))
                        + sseEvent("6", StatusCodes.USER_LEAVE, "senderId=9&message=")
                        + ": ping\n\n",
                200
        )) {
            WebserverTransport transport = new WebserverTransport();
            RecordingListener listener = new RecordingListener();
            transport.connect(new Config(server.baseUrl(), 0, "Me", ACCESS_KEY, ROOM_SECRET, "Room", true), listener, Runnable::run);

            assertTrue(listener.connected.await(5, TimeUnit.SECONDS));
            transport.sendMessage("Hi");
            transport.sendTyping(900);

            assertTrue(listener.text.await(5, TimeUnit.SECONDS));
            assertTrue(listener.joined.await(5, TimeUnit.SECONDS));
            assertTrue(listener.typing.await(5, TimeUnit.SECONDS));
            assertTrue(listener.file.await(5, TimeUnit.SECONDS));
            assertTrue(listener.left.await(5, TimeUnit.SECONDS));
            assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS));
            assertTrue(server.messageReceived.await(5, TimeUnit.SECONDS));
            assertTrue(server.typingReceived.await(5, TimeUnit.SECONDS));
            assertTrue(server.heartbeatReceived.await(5, TimeUnit.SECONDS));

            transport.join(5_000);
            transport.disconnect();
            assertTrue(server.disconnectReceived.await(5, TimeUnit.SECONDS));

            assertEquals(7, listener.connectedOwnId);
            assertEquals("Me", listener.connectedUsers.get(7));
            assertEquals("Alex", listener.connectedUsers.get(8));
            assertEquals(250, listener.messageCharacterLimit);
            assertEquals(4096, listener.fileSizeBytesLimit);
            assertTrue(listener.fileTransferEnabled);
            assertEquals("Hello", listener.textMessage);
            assertEquals(8, listener.textSenderId);
            assertEquals("Chris", listener.joinedName);
            assertEquals(9, listener.joinedUserId);
            assertEquals(8, listener.typingSenderId);
            assertTrue(listener.typingExpiresAt > System.currentTimeMillis());
            assertNotNull(listener.filePacket);
            assertEquals("file-1", listener.filePacket.fileId());
            assertEquals("name.txt", listener.filePacket.fileName());
            assertEquals(server.baseUrl() + "/public/index.php/download/file-1", listener.filePacket.payload());
            assertEquals(9, listener.leftUserId);
            assertEquals(6, transport.lastEventId());
            assertEquals("token-123", server.messageToken);
            assertEquals("Hi", symmetric.decryptChatMessage(jsonValue(server.messageBody, "message")));
            assertEquals("token-123", server.typingToken);
            assertEquals("900", symmetric.decryptMessage(jsonValue(server.typingBody, "payload")));
            assertEquals("token-123", server.heartbeatToken);
            assertEquals("6", server.heartbeatAckEventId);
            assertEquals("token-123", server.disconnectToken);
            assertEquals("6", server.disconnectAckEventId);
        }
    }

    @Test
    void connectUsesFallbackNameWhenDecryptingUsersFails() throws Exception {
        SymmetricEncryption symmetric = new SymmetricEncryption(ROOM_SECRET, SESSION_NONCE);
        try (TestWebserver server = TestWebserver.transport(
                "sessionNonce=" + urlEncode(SESSION_NONCE),
                List.of(
                        "token=token-321",
                        "clientId=4",
                        "messageCharacterLimit=99",
                        "fileSizeBytesLimit=1024",
                        "fileTransferEnabled=false",
                        "user.4=" + urlEncode(symmetric.encryptMessage("Jamie")),
                        "user.5=not-valid"
                ),
                "",
                0
        )) {
            WebserverTransport transport = new WebserverTransport();
            RecordingListener listener = new RecordingListener();
            transport.connect(new Config(server.baseUrl(), 0, "Jamie", ACCESS_KEY, ROOM_SECRET, "Room", false), listener, Runnable::run);

            assertTrue(listener.connected.await(5, TimeUnit.SECONDS));
            assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS));
            transport.join(5_000);

            assertEquals("Jamie", listener.connectedUsers.get(4));
            assertEquals("Unknown user", listener.connectedUsers.get(5));
        }
    }

    @Test
    void connectReportsHandshakeFailureReason() throws Exception {
        try (TestWebserver server = TestWebserver.transport("{\"error\":\"Access denied\"}", List.of(), "", 0)) {
            server.handshakeStatus = 403;

            WebserverTransport transport = new WebserverTransport();
            RecordingListener listener = new RecordingListener();
            transport.connect(new Config(server.baseUrl(), 0, "Me", ACCESS_KEY, ROOM_SECRET, "Room", false), listener, Runnable::run);

            assertTrue(listener.connectionFailed.await(5, TimeUnit.SECONDS));
            transport.join(5_000);

            assertEquals("Access denied", listener.connectionFailedReason);
        }
    }

    @Test
    void authorizeAddsBearerHeaderAndClampsLastEventId() throws Exception {
        WebserverTransport transport = configuredTransport("http://localhost", "room", "token-1", new SymmetricEncryption("", "nonce"));

        transport.setLastEventId(-10);
        assertEquals(0, transport.lastEventId());

        transport.setLastEventId(14);
        HttpRequest request = transport.authorize(HttpRequest.newBuilder(URI.create("http://localhost/test"))).build();

        assertEquals(14, transport.lastEventId());
        assertEquals("token-1", request.headers().firstValue("X-ThatsApp-Token").orElseThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void privateHelpersNormalizeUrlsAndParsePayloads() throws Exception {
        Method normalizeBaseUrl = WebserverTransport.class.getDeclaredMethod("normalizeBaseUrl", String.class);
        normalizeBaseUrl.setAccessible(true);
        Method parseKvLines = WebserverTransport.class.getDeclaredMethod("parseKvLines", String.class);
        parseKvLines.setAccessible(true);
        Method parseQueryString = WebserverTransport.class.getDeclaredMethod("parseQueryString", String.class);
        parseQueryString.setAccessible(true);
        Method extractError = WebserverTransport.class.getDeclaredMethod("extractError", String.class, int.class);
        extractError.setAccessible(true);
        Method hashSessionName = WebserverTransport.class.getDeclaredMethod("hashSessionName", String.class, String.class);
        hashSessionName.setAccessible(true);

        Map<String, String> kv = (Map<String, String>) parseKvLines.invoke(null, "alpha=1\nbroken\nbeta=2\n");
        Map<String, String> query = (Map<String, String>) parseQueryString.invoke(null, "senderId=7&message=hi&broken");
        String hashed = (String) hashSessionName.invoke(null, "room", "secret");

        assertEquals("https://example.com/public/index.php", normalizeBaseUrl.invoke(null, "https://example.com///"));
        assertEquals(Map.of("alpha", "1", "beta", "2"), kv);
        assertEquals(Map.of("senderId", "7", "message", "hi"), query);
        assertEquals("Nope", extractError.invoke(null, "{\"error\":\"Nope\"}", 403));
        assertEquals("HTTP 500", extractError.invoke(null, "\"\"", 500));
        assertEquals("room", hashSessionName.invoke(null, "room", ""));
        assertEquals(64, hashed.length());
    }

    private static WebserverTransport configuredTransport(String baseUrl, String sessionName, String token, SymmetricEncryption symmetric)
            throws Exception {
        WebserverTransport transport = new WebserverTransport();
        setField(transport, "baseUrl", baseUrl);
        setField(transport, "sessionName", sessionName);
        setField(transport, "token", token);
        setField(transport, "symmetric", symmetric);
        return transport;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = WebserverTransport.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String sseEvent(String id, String event, String data) {
        return "id: " + id + "\n"
                + "event: " + event + "\n"
                + "data: " + data + "\n\n";
    }

    private static String jsonValue(String body, String key) {
        int keyIndex = body.indexOf("\"" + key + "\"");
        int colon = body.indexOf(':', keyIndex);
        int start = body.indexOf('"', colon + 1);
        int end = body.indexOf('"', start + 1);
        return body.substring(start + 1, end);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class RecordingListener implements CommunicationClientListener {
        private final CountDownLatch connected = new CountDownLatch(1);
        private final CountDownLatch connectionFailed = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final CountDownLatch text = new CountDownLatch(1);
        private final CountDownLatch joined = new CountDownLatch(1);
        private final CountDownLatch left = new CountDownLatch(1);
        private final CountDownLatch typing = new CountDownLatch(1);
        private final CountDownLatch file = new CountDownLatch(1);
        private int connectedOwnId;
        private Map<Integer, String> connectedUsers;
        private long messageCharacterLimit;
        private long fileSizeBytesLimit;
        private boolean fileTransferEnabled;
        private String connectionFailedReason = "";
        private int textSenderId;
        private String textMessage = "";
        private int joinedUserId;
        private String joinedName = "";
        private int leftUserId;
        private int typingSenderId;
        private long typingExpiresAt;
        private FilePacket filePacket;

        @Override
        public void onConnected(int ownId, Map<Integer, String> users, long messageCharacterLimit, long fileSizeBytesLimit, boolean fileTransferEnabled) {
            connectedOwnId = ownId;
            connectedUsers = Map.copyOf(users);
            this.messageCharacterLimit = messageCharacterLimit;
            this.fileSizeBytesLimit = fileSizeBytesLimit;
            this.fileTransferEnabled = fileTransferEnabled;
            connected.countDown();
        }

        @Override
        public void onConnectionFailed(String reason) {
            connectionFailedReason = reason;
            connectionFailed.countDown();
        }

        @Override
        public void onDisconnected() {
            disconnected.countDown();
        }

        @Override
        public void onDisconnectionFailed() {
        }

        @Override
        public void onTextMessage(int senderId, String message) {
            textSenderId = senderId;
            textMessage = message;
            text.countDown();
        }

        @Override
        public void onServerMessage(String message) {
        }

        @Override
        public void onUserJoined(int userId, String userName) {
            joinedUserId = userId;
            joinedName = userName;
            joined.countDown();
        }

        @Override
        public void onUserLeft(int userId) {
            leftUserId = userId;
            left.countDown();
        }

        @Override
        public void onTyping(int senderId, long expiresAt) {
            typingSenderId = senderId;
            typingExpiresAt = expiresAt;
            typing.countDown();
        }

        @Override
        public void onFilePacket(FilePacket packet) {
            filePacket = packet;
            file.countDown();
        }

        @Override
        public void onShutdownRequested(int code) {
        }
    }

}
