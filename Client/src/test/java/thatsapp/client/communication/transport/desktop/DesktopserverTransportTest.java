package thatsapp.client.communication.transport.desktop;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.transport.CommunicationClientListener;
import thatsapp.client.communication.transport.Config;
import thatsapp.common.CryptoUtils;
import thatsapp.common.FilePacket;
import thatsapp.common.Message;
import thatsapp.common.StatusCodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopserverTransportTest {

    private static final String ACCESS_KEY = "desktop-key";
    private static final String ROOM_SECRET = "desktop-room";

    @Test
    void connectProcessesHandshakeMessagesAndOutgoingTraffic() throws Exception {
        RecordingListener listener = new RecordingListener();
        FilePacket outboundPacket = new FilePacket(StatusCodes.FILE_META, "file-1", "upload.txt", 5, -1, 0, "payload", 1, "");

        try (DesktopTestServer server = new DesktopTestServer(socket -> runSuccessfulSession(socket, outboundPacket))) {
            DesktopserverTransport transport = new DesktopserverTransport();
            transport.connect(new Config("127.0.0.1", server.port(), "Jamie", ACCESS_KEY, ROOM_SECRET, "Room", false), listener, Runnable::run);

            assertTrue(listener.connected.await(5, TimeUnit.SECONDS));
            transport.sendMessage("Hi");
            transport.sendTyping(1200);
            transport.sendFilePacket(outboundPacket);

            assertTrue(listener.text.await(5, TimeUnit.SECONDS));
            assertTrue(listener.joined.await(5, TimeUnit.SECONDS));
            assertTrue(listener.typing.await(5, TimeUnit.SECONDS));
            assertTrue(listener.serverMessage.await(5, TimeUnit.SECONDS));
            assertTrue(listener.file.await(5, TimeUnit.SECONDS));
            assertTrue(listener.left.await(5, TimeUnit.SECONDS));
            assertTrue(listener.shutdown.await(5, TimeUnit.SECONDS));
            assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS));
            transport.join(5_000);

            SessionResult result = server.result();
            assertEquals("Jamie", result.registeredUserName());
            assertEquals("Hi", result.sentChatMessage());
            assertEquals("1200", result.sentTypingValue());
            assertEquals(outboundPacket, result.sentFilePacket());
            assertEquals(5, listener.connectedOwnId);
            assertEquals("Jamie", listener.connectedUsers.get(5));
            assertEquals("Unknown user", listener.connectedUsers.get(7));
            assertEquals(77, listener.messageCharacterLimit);
            assertEquals(8192, listener.fileSizeBytesLimit);
            assertFalse(listener.fileTransferEnabled);
            assertEquals("Hello", listener.textMessage);
            assertEquals(7, listener.textSenderId);
            assertEquals("Chris", listener.joinedName);
            assertEquals(8, listener.joinedUserId);
            assertEquals(7, listener.typingSenderId);
            assertTrue(listener.typingExpiresAt > System.currentTimeMillis());
            assertEquals("Notice", listener.serverMessageText);
            assertNotNull(listener.filePacket);
            assertEquals("name.txt", listener.filePacket.fileName());
            assertEquals(8, listener.leftUserId);
            assertEquals(-99, listener.shutdownCode);
        }
    }

    @Test
    void connectReportsHandshakeFailureReason() throws Exception {
        RecordingListener listener = new RecordingListener();

        try (DesktopTestServer server = new DesktopTestServer(DesktopserverTransportTest::runHandshakeFailureSession)) {
            DesktopserverTransport transport = new DesktopserverTransport();
            transport.connect(new Config("127.0.0.1", server.port(), "Jamie", ACCESS_KEY, ROOM_SECRET, "Room", false), listener, Runnable::run);

            assertTrue(listener.connectionFailed.await(5, TimeUnit.SECONDS));
            transport.join(5_000);

            assertEquals("Rejected", listener.connectionFailedReason);
        }
    }

    @Test
    void createInputFilterAllowsKnownPayloadsAndRejectsUnsafeOnes() throws Exception {
        Method createInputFilter = DesktopserverTransport.class.getDeclaredMethod("createInputFilter");
        createInputFilter.setAccessible(true);
        ObjectInputFilter filter = (ObjectInputFilter) createInputFilter.invoke(null);

        assertEquals(ObjectInputFilter.Status.ALLOWED, filter.checkInput(new FilterInfoStub(Message.class, -1, 1, 1, 10)));
        assertEquals(ObjectInputFilter.Status.ALLOWED, filter.checkInput(new FilterInfoStub(FilePacket.class, -1, 1, 1, 10)));
        assertEquals(ObjectInputFilter.Status.ALLOWED, filter.checkInput(new FilterInfoStub(FilePacket.class, -1, 1, 500_000, 500_000_000)));
        assertEquals(ObjectInputFilter.Status.ALLOWED, filter.checkInput(new FilterInfoStub(long[].class, 5, 1, 1, 10)));
        assertEquals(ObjectInputFilter.Status.REJECTED, filter.checkInput(new FilterInfoStub(String[].class, 5, 1, 1, 10)));
        assertEquals(ObjectInputFilter.Status.REJECTED, filter.checkInput(new FilterInfoStub(Message.class, -1, 11, 1, 10)));
        assertEquals(ObjectInputFilter.Status.UNDECIDED, filter.checkInput(new FilterInfoStub(null, -1, 1, 1, 10)));
    }

    private static SessionResult runSuccessfulSession(Socket socket, FilePacket outboundPacket) throws Exception {
        socket.setSoTimeout(5_000);
        try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            out.flush();

            String challenge = "server-challenge";
            out.writeObject(new Message(challenge + "|", 0, StatusCodes.HANDSHAKE_CHALLENGE));
            out.flush();

            Message handshakeResponse = (Message) in.readObject();
            String[] responseParts = handshakeResponse.message().split("\\|", -1);
            String sessionNonce = responseParts[1];
            String clientNonce = responseParts[2];
            String clientProof = responseParts[0];
            assertEquals(StatusCodes.HANDSHAKE_RESPONSE, handshakeResponse.status());
            assertFalse(sessionNonce.isBlank());
            assertFalse(clientNonce.isBlank());
            assertEquals("true", responseParts[3]);
            assertTrue(CryptoUtils.matchesBase64HmacSha256(ACCESS_KEY, "client|" + challenge + "|" + clientNonce, clientProof));

            out.writeObject(new Message(
                    sessionNonce + "|" + CryptoUtils.hmacSha256Base64(ACCESS_KEY, "server|" + challenge + "|" + clientNonce + "|" + sessionNonce),
                    0,
                    StatusCodes.HANDSHAKE_OK
            ));
            out.flush();

            SymmetricEncryption symmetric = new SymmetricEncryption(ROOM_SECRET, sessionNonce);
            Message register = (Message) in.readObject();
            String registeredUserName = symmetric.decryptMessage(register.message());

            HashMap<Integer, String> users = new HashMap<>();
            users.put(5, symmetric.encryptMessage("Jamie"));
            users.put(7, "not-valid");

            out.writeObject(5);
            out.writeObject(users);
            out.writeObject(new long[]{77, 8192, 0});
            out.flush();

            Message sentChat = awaitMessage(in);
            Message sentTyping = awaitMessage(in);
            FilePacket sentFilePacket = awaitFilePacket(in);

            out.writeObject(new Message(symmetric.encryptChatMessage("Hello"), 7, StatusCodes.MESSAGE));
            out.writeObject(new Message(symmetric.encryptMessage("Chris"), 8, StatusCodes.USER_JOIN));
            out.writeObject(new Message(symmetric.encryptMessage("2500"), 7, StatusCodes.USER_TYPING));
            out.writeObject(new Message("Notice", 0, StatusCodes.SERVER_MESSAGE));
            out.writeObject(new FilePacket(StatusCodes.FILE_META, "file-2", "name.txt", 4, -1, 0, "payload", 7, ""));
            out.writeObject(new Message("", 8, StatusCodes.USER_LEAVE));
            out.writeObject(new Message("-99", 0, StatusCodes.SERVER_MESSAGE));
            out.writeObject(null);
            out.flush();

            return new SessionResult(
                    registeredUserName,
                    symmetric.decryptChatMessage(sentChat.message()),
                    symmetric.decryptMessage(sentTyping.message()),
                    sentFilePacket
            );
        }
    }

    private static SessionResult runHandshakeFailureSession(Socket socket) throws Exception {
        socket.setSoTimeout(5_000);
        try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            out.flush();
            out.writeObject(new Message("challenge|provided-nonce", 0, StatusCodes.HANDSHAKE_CHALLENGE));
            out.flush();
            in.readObject();
            out.writeObject(new Message("Rejected", 0, StatusCodes.HANDSHAKE_FAIL));
            out.flush();
            return new SessionResult("", "", "", null);
        }
    }

    private static Message awaitMessage(ObjectInputStream in) throws IOException, ClassNotFoundException {
        Object payload = in.readObject();
        if (payload instanceof Message message) {
            return message;
        }
        throw new IllegalStateException("Expected Message but got " + payload);
    }

    private static FilePacket awaitFilePacket(ObjectInputStream in) throws IOException, ClassNotFoundException {
        Object payload = in.readObject();
        if (payload instanceof FilePacket filePacket) {
            return filePacket;
        }
        throw new IllegalStateException("Expected FilePacket but got " + payload);
    }

    private static final class RecordingListener implements CommunicationClientListener {
        private final CountDownLatch connected = new CountDownLatch(1);
        private final CountDownLatch connectionFailed = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);
        private final CountDownLatch text = new CountDownLatch(1);
        private final CountDownLatch joined = new CountDownLatch(1);
        private final CountDownLatch left = new CountDownLatch(1);
        private final CountDownLatch typing = new CountDownLatch(1);
        private final CountDownLatch serverMessage = new CountDownLatch(1);
        private final CountDownLatch file = new CountDownLatch(1);
        private final CountDownLatch shutdown = new CountDownLatch(1);
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
        private String serverMessageText = "";
        private FilePacket filePacket;
        private int shutdownCode;

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
            serverMessageText = message;
            serverMessage.countDown();
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
            shutdownCode = code;
            shutdown.countDown();
        }
    }

    private static final class DesktopTestServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final CompletableFuture<SessionResult> result;

        private DesktopTestServer(SessionScript script) throws IOException {
            serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            result = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = serverSocket.accept()) {
                    return script.run(socket);
                } catch (SocketTimeoutException e) {
                    throw new IllegalStateException("Socket timed out", e);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private SessionResult result() throws Exception {
            return result.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            result();
        }
    }

    @FunctionalInterface
    private interface SessionScript {
        SessionResult run(Socket socket) throws Exception;
    }

    private record FilterInfoStub(
            Class<?> serialClass,
            long arrayLength,
            long depth,
            long references,
            long streamBytes
    ) implements ObjectInputFilter.FilterInfo {
    }

    private record SessionResult(
            String registeredUserName,
            String sentChatMessage,
            String sentTypingValue,
            FilePacket sentFilePacket
    ) {
    }
}
