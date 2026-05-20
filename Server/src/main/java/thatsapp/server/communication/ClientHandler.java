package thatsapp.server.communication;

import javafx.application.Platform;
import thatsapp.common.ChatMessagePayload;
import thatsapp.common.FilePacket;
import thatsapp.common.Message;
import thatsapp.common.StatusCodes;
import thatsapp.common.Logger;
import thatsapp.server.messages.ServerLogMessages;
import thatsapp.server.ui.controllers.MainController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;

public class ClientHandler extends Thread {

    private static final String PRE_AUTH_SOCKET_TIMEOUT_PROPERTY = "thatsapp.server.preAuthSocketTimeoutMs";
    private static final int DEFAULT_PRE_AUTH_SOCKET_TIMEOUT_MS = 10_000;
    private static final int RESET_INTERVAL = 64;
    private final Socket clientSocket;
    private final Server server;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private final int clientId;
    private final String accessKey;
    private final Object writeLock = new Object();
    private volatile boolean readyForMessages = false;
    private int writesSinceReset = 0;

    public ClientHandler(Socket socket, int clientId, Server server, String accessKey) {
        this.clientSocket = socket;
        this.server = server;
        this.clientId = clientId;
        this.accessKey = accessKey;
    }

    @Override
    public void run() {
        boolean registered = false;
        try {
            applyPreAuthReadTimeout();
            out = new ObjectOutputStream(clientSocket.getOutputStream());
            in = new ObjectInputStream(clientSocket.getInputStream());
            in.setObjectInputFilter(createInputFilter());

            if (!performHandshake()) {
                return;
            }

            Object registrationPayload = in.readObject();
            if (!(registrationPayload instanceof Message message)
                    || !StatusCodes.REGISTER.equals(message.status())
                    || message.message() == null
                    || message.message().isBlank()) {
                String warning = ServerLogMessages.clientSentInvalidRegistrationMessage(clientId);
                Logger.warn(warning);
                appendControllerMessage(warning);
                return;
            }
            server.registerClient(clientId, message.message());
            registered = true;
            clearReadTimeout();

            writeToClient(clientId, true);
            writeToClient(new HashMap<>(server.getClientNames()), true);
            writeToClient(new long[]{
                    server.getRules().messageCharacterLimit(),
                    server.getRules().fileSizeBytesLimit(),
                    server.isFileTransferEnabled() ? 1L : 0L,
            }, true);
            readyForMessages = true;

            String remoteIp = clientSocket.getInetAddress() != null
                    ? clientSocket.getInetAddress().getHostAddress()
                    : "unknown";
            String connectedMsg = ServerLogMessages.clientConnected(clientId, remoteIp);
            Logger.info(connectedMsg);
            appendControllerMessage(connectedMsg);

            Object incoming;
            while ((incoming = in.readObject()) != null) {
                if (incoming instanceof Message msg) {
                    String status = msg.status();
                    if (StatusCodes.MESSAGE.equals(status)) {
                        String rejection = validateChatMessage(msg.message());
                        if (rejection != null) {
                            rejectChatMessage(rejection);
                            continue;
                        }
                        appendControllerMessage("Client " + clientId + " sent a message.");
                        server.broadcast(msg, clientId, StatusCodes.MESSAGE);
                    } else if (StatusCodes.USER_TYPING.equals(status)) {
                        server.broadcast(msg, clientId, StatusCodes.USER_TYPING);
                    }
                } else if (incoming instanceof FilePacket filePacket) {
                    server.getFileTransferManager().handlePacketFromClient(clientId, filePacket);
                }
            }

        } catch (SocketTimeoutException e) {
            String message = ServerLogMessages.clientTimedOutDuringSetup(clientId);
            Logger.warn(message);
            appendControllerMessage(message);
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_PROCESSING_CLIENT_MESSAGES_PREFIX, e), e);
        } finally {
            if (registered) {
                server.removeClient(clientId);
            } else {
                server.dropClientSilently(clientId);
            }
            closeConnection();
        }
    }

    public void sendMessage(Message message) {
        try {
            writeToClient(message, false);
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_SENDING_MESSAGE_TO_CLIENT_PREFIX, e), e);
        }
    }

    public void sendClientName(String clientName, int clientId) {
        try {
            Message message = new Message(clientName, clientId, StatusCodes.USER_JOIN);
            writeToClient(message, false);
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_SENDING_CLIENT_NAME_TO_CLIENT_PREFIX, e), e);
        }
    }

    public void sendFilePacket(FilePacket packet) {
        try {
            writeToClient(packet, true);
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_SENDING_FILE_PACKET_TO_CLIENT_PREFIX, e), e);
        }
    }

    public void removeClientName(int clientId) {
        try {
            writeToClient(new Message("", clientId, StatusCodes.USER_LEAVE), false);
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_REMOVING_CLIENT_NAME_PREFIX, e), e);
        }
    }

    public void closeConnection() {
        try {
            readyForMessages = false;
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
            Logger.info(ServerLogMessages.connectionWithClientClosed(clientId));
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_CLOSING_CLIENT_CONNECTION_PREFIX, e), e);
        }
    }

    public boolean isReady() {
        return readyForMessages;
    }

    private boolean performHandshake() {
        try {
            String challenge = server.generateChallenge();
            String payload = challenge + "|" + server.getSessionNonce();
            writeToClient(new Message(payload, 0, StatusCodes.HANDSHAKE_CHALLENGE), true);

            Message response = (Message) in.readObject();
            if (!StatusCodes.HANDSHAKE_RESPONSE.equals(response.status())) {
                writeToClient(new Message("Invalid handshake response", 0, StatusCodes.HANDSHAKE_FAIL), true);
                return false;
            }
            String[] parts = response.message().split("\\|", -1);
            if (parts.length < 4) {
                writeToClient(new Message("Invalid handshake response", 0, StatusCodes.HANDSHAKE_FAIL), true);
                return false;
            }
            String proof = parts[0];
            String proposedNonce = parts[1];
            String clientNonce = parts[2];
            String chatEncryptedFlag = parts[3];
            if (clientNonce.isBlank() || !verifyProof(accessKey, "client|" + challenge + "|" + clientNonce, proof)) {
                writeToClient(new Message("Access key mismatch", 0, StatusCodes.HANDSHAKE_FAIL), true);
                String message = ServerLogMessages.clientFailedHandshake(clientId);
                Logger.warn(message);
                appendControllerMessage(message);
                return false;
            }
            if (!"true".equals(chatEncryptedFlag) && !"false".equals(chatEncryptedFlag)) {
                writeToClient(new Message("Invalid handshake response", 0, StatusCodes.HANDSHAKE_FAIL), true);
                return false;
            }
            if (!server.ensureChatEncrypted(Boolean.parseBoolean(chatEncryptedFlag))) {
                writeToClient(new Message("Chat encryption mode mismatch", 0, StatusCodes.HANDSHAKE_FAIL), true);
                return false;
            }
            if (!server.tryAdmitClient(clientId)) {
                String message = ServerLogMessages.clientRejectedUserLimitReached(clientId);
                Logger.warn(message);
                appendControllerMessage(message);
                writeToClient(new Message("Server full", 0, StatusCodes.HANDSHAKE_FAIL), true);
                return false;
            }
            String agreedNonce = server.ensureSessionNonce(proposedNonce);
            String serverProof = hmac(accessKey, "server|" + challenge + "|" + clientNonce + "|" + agreedNonce);
            writeToClient(new Message(agreedNonce + "|" + serverProof, 0, StatusCodes.HANDSHAKE_OK), true);
            return true;
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.HANDSHAKE_FAILED_PREFIX, e), e);
            return false;
        }
    }

    private void applyPreAuthReadTimeout() throws IOException {
        clientSocket.setSoTimeout(resolvePreAuthSocketTimeoutMs());
    }

    private void clearReadTimeout() throws IOException {
        clientSocket.setSoTimeout(0);
    }

    private void writeToClient(Object payload, boolean forceReset) throws IOException {
        synchronized (writeLock) {
            out.writeUnshared(payload);
            out.flush();
            writesSinceReset++;
            if (forceReset || writesSinceReset >= RESET_INTERVAL) {
                out.reset();
                writesSinceReset = 0;
            }
        }
    }

    private static int resolvePreAuthSocketTimeoutMs() {
        String configured = System.getProperty(PRE_AUTH_SOCKET_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_PRE_AUTH_SOCKET_TIMEOUT_MS;
        }
        try {
            int timeout = Integer.parseInt(configured);
            return timeout > 0 ? timeout : DEFAULT_PRE_AUTH_SOCKET_TIMEOUT_MS;
        } catch (NumberFormatException e) {
            return DEFAULT_PRE_AUTH_SOCKET_TIMEOUT_MS;
        }
    }

    private String hmac(String key, String data) throws Exception {
        if (key == null || key.isBlank()) {
            return "";
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean verifyProof(String key, String data, String proof) throws Exception {
        byte[] expected = Base64.getDecoder().decode(hmac(key, data));
        byte[] provided = Base64.getDecoder().decode(proof);
        return MessageDigest.isEqual(expected, provided);
    }

    private String validateChatMessage(String payload) {
        int characterLimit = server.getRules().messageCharacterLimit();
        if (server.isChatEncrypted() != ChatMessagePayload.isEncrypted(payload)) {
            return "Invalid message payload";
        }
        try {
            int count = ChatMessagePayload.countPayloadCharacters(payload);
            if (characterLimit > 0 && count > characterLimit) {
                return "Message exceeds limit of " + characterLimit + " characters.";
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "Invalid message payload";
        }
    }

    private void rejectChatMessage(String reason) {
        String message = ServerLogMessages.rejectedMessageFromClient(clientId, reason);
        Logger.warn(message);
        appendControllerMessage(message);
        try {
            writeToClient(new Message(reason, 0, StatusCodes.SERVER_MESSAGE), false);
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_SENDING_MESSAGE_TO_CLIENT_PREFIX, e), e);
        }
    }

    private static ObjectInputFilter createInputFilter() {
        return info -> {
            if (info.depth() > 10) {
                return ObjectInputFilter.Status.REJECTED;
            }
            Class<?> clazz = info.serialClass();
            if (clazz == null) {
                return ObjectInputFilter.Status.UNDECIDED;
            }
            if (clazz == Message.class
                    || clazz == FilePacket.class
                    || clazz == String.class
                    || clazz == Integer.class
                    || clazz == Long.class) {
                return ObjectInputFilter.Status.ALLOWED;
            }
            return ObjectInputFilter.Status.REJECTED;
        };
    }

    private void appendControllerMessage(String message) {
        MainController controller = server.getController();
        if (controller == null) {
            return;
        }
        Platform.runLater(() -> controller.appendMessage(message));
    }
}
