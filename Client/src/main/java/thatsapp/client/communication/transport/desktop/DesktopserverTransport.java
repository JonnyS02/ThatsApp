package thatsapp.client.communication.transport.desktop;

import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.transport.CommunicationClient;
import thatsapp.client.communication.transport.CommunicationClientListener;
import thatsapp.client.communication.transport.Config;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.CryptoUtils;
import thatsapp.common.Logger;
import thatsapp.common.FilePacket;
import thatsapp.common.Message;
import thatsapp.common.StatusCodes;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

public class DesktopserverTransport extends Thread implements CommunicationClient {

    private static final String UNKNOWN_USER_NAME = "Unknown user";
    private static final long CONNECT_TIMEOUT_MS = 300_000L;
    private static final long CONNECT_RETRY_DELAY_MS = 1_000L;
    private static final int RESET_INTERVAL = 64;
    private static final long MAX_ARRAY_LENGTH = 10_000;

    private Socket socket;
    private ObjectInputStream inputStream;
    private ObjectOutputStream outputStream;
    private boolean isConnected = false;
    private volatile boolean cancelRequested = false;
    private SymmetricEncryption symmetric;
    private final Object writeLock = new Object();
    private int writesSinceReset = 0;

    private Config config;
    private CommunicationClientListener listener;
    private Executor callbackExecutor;

    @Override
    public void connect(Config config, CommunicationClientListener listener, Executor callbackExecutor) {
        this.config = config;
        this.listener = listener;
        this.callbackExecutor = callbackExecutor;
        start();
    }

    @Override
    public void requestCancel() {
        cancelRequested = true;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_CANCELLING_CONNECTION_ATTEMPT_PREFIX, e), e);
        }
    }

    @Override
    public void disconnect() {
        closeConnection();
    }

    @Override
    public SymmetricEncryption getSymmetric() {
        return symmetric;
    }

    @Override
    public void sendMessage(String message) {
        try {
            String encryptedMessage = symmetric.encryptChatMessage(message);
            writeToServer(new Message(encryptedMessage, 0, StatusCodes.MESSAGE), false);
        } catch (IOException e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_SENDING_MESSAGE_PREFIX, e), e);
        }
    }

    @Override
    public void sendTyping(long ttlMs) {
        try {
            String encrypted = symmetric.encryptMessage(String.valueOf(ttlMs));
            writeToServer(new Message(encrypted, 0, StatusCodes.USER_TYPING), false);
        } catch (IOException e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_SENDING_TYPING_STATUS_PREFIX, e), e);
        }
    }

    @Override
    public void sendFilePacket(FilePacket packet) throws IOException {
        writeToServer(packet, true);
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        while (!isConnected && !cancelRequested && (System.currentTimeMillis() - startTime) < CONNECT_TIMEOUT_MS) {
            try {
                Logger.info(ClientLogMessages.ATTEMPTING_TO_CONNECT_TO_THE_SERVER);
                socket = new Socket(config.host(), config.port());
                isConnected = true;
                Logger.info(ClientLogMessages.CONNECTION_ESTABLISHED);
            } catch (Exception e) {
                Logger.warn(ClientLogMessages.CONNECTION_FAILED_RETRYING);
                try {
                    Thread.sleep(CONNECT_RETRY_DELAY_MS);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    cancelRequested = true;
                }
            }
        }

        if (!isConnected) {
            String reason = cancelRequested ? ClientLogMessages.CONNECTION_CANCELED : ClientLogMessages.COULD_NOT_ESTABLISH_CONNECTION;
            Logger.error(reason);
            callbackExecutor.execute(() -> listener.onConnectionFailed(reason));
            return;
        }

        boolean handshakeCompleted = false;
        String failureReason = null;
        try {
            outputStream = new ObjectOutputStream(socket.getOutputStream());
            inputStream = new ObjectInputStream(socket.getInputStream());
            inputStream.setObjectInputFilter(createInputFilter());

            performHandshake();

            String encryptedName = symmetric.encryptMessage(config.userName());
            writeToServer(new Message(encryptedName, 0, StatusCodes.REGISTER), true);

            int ownId = (int) inputStream.readObject();

            Map<Integer, String> encryptedUsers = (Map<Integer, String>) inputStream.readObject();
            Map<Integer, String> decryptedUsers = new HashMap<>();
            String ownUserName = config.userName() == null || config.userName().isBlank() ? UNKNOWN_USER_NAME : config.userName();
            decryptedUsers.put(0, "Server");
            for (Map.Entry<Integer, String> entry : encryptedUsers.entrySet()) {
                String fallbackName = entry.getKey() == ownId ? ownUserName : UNKNOWN_USER_NAME;
                decryptedUsers.put(entry.getKey(), decryptUserName(entry.getValue(), entry.getKey(), fallbackName));
            }

            long[] rules = (long[]) inputStream.readObject();
            if (rules.length < 3) {
                throw new IllegalStateException("Invalid server rules.");
            }
            long messageCharacterLimit = rules[0];
            long fileSizeBytesLimit = rules[1];
            boolean fileTransferEnabled = rules[2] != 0;
            callbackExecutor.execute(() -> listener.onConnected(ownId, decryptedUsers, messageCharacterLimit, fileSizeBytesLimit, fileTransferEnabled));

            handshakeCompleted = true;

            Object incoming;
            while ((incoming = inputStream.readObject()) != null) {
                if (incoming instanceof Message message) {
                    messageHandler(message);
                } else if (incoming instanceof FilePacket packet) {
                    callbackExecutor.execute(() -> listener.onFilePacket(packet));
                }
            }
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_DURING_COMMUNICATION_PREFIX, e), e);
            if (!handshakeCompleted) {
                failureReason = e.getMessage();
            }
        } finally {
            if (handshakeCompleted) {
                callbackExecutor.execute(listener::onDisconnected);
            } else {
                String reason = failureReason == null || failureReason.isBlank() ? "Connection failed" : failureReason;
                callbackExecutor.execute(() -> listener.onConnectionFailed(reason));
            }
            closeConnection();
        }
    }

    private void messageHandler(Message message) {
        String status = message.status();
        try {
            if (StatusCodes.MESSAGE.equals(status)) {
                String decryptedMessage = symmetric.decryptChatMessage(message.message());
                int senderId = message.senderId();
                callbackExecutor.execute(() -> listener.onTextMessage(senderId, decryptedMessage));
            } else if (StatusCodes.USER_JOIN.equals(status)) {
                int id = message.senderId();
                String finalName = decryptUserName(message.message(), id, UNKNOWN_USER_NAME);
                callbackExecutor.execute(() -> listener.onUserJoined(id, finalName));
            } else if (StatusCodes.USER_LEAVE.equals(status)) {
                int id = message.senderId();
                callbackExecutor.execute(() -> listener.onUserLeft(id));
            } else if (StatusCodes.SERVER_MESSAGE.equals(status)) {
                if ("-99".equals(message.message())) {
                    callbackExecutor.execute(() -> listener.onShutdownRequested(-99));
                    return;
                }
                String serverText = message.message();
                callbackExecutor.execute(() -> listener.onServerMessage(serverText));
            } else if (StatusCodes.USER_TYPING.equals(status)) {
                long ttlMs;
                try {
                    String decrypted = symmetric.decryptMessage(message.message());
                    ttlMs = Long.parseLong(decrypted);
                } catch (Exception e) {
                    Logger.warn(ClientLogMessages.couldNotProcessTypingStatus(message.senderId()));
                    return;
                }
                long expiresAt = ttlMs <= 0 ? 0 : System.currentTimeMillis() + ttlMs;
                int senderId = message.senderId();
                callbackExecutor.execute(() -> listener.onTyping(senderId, expiresAt));
            }
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_HANDLING_MESSAGE_PREFIX, e), e);
        }
    }

    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                Logger.info(ClientLogMessages.CLOSING_CONNECTION);
                socket.close();
            }
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            isConnected = false;
            Logger.info(ClientLogMessages.CONNECTION_CLOSED_SUCCESSFULLY);
        } catch (IOException e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_CLOSING_CONNECTION_PREFIX, e), e);
            callbackExecutor.execute(listener::onDisconnectionFailed);
        }
    }

    private void performHandshake() throws Exception {
        Message challengeMessage = (Message) inputStream.readObject();
        if (!StatusCodes.HANDSHAKE_CHALLENGE.equals(challengeMessage.status())) {
            throw new IllegalStateException("Invalid handshake challenge.");
        }
        String[] parts = challengeMessage.message().split("\\|", -1);
        if (parts.length < 2) {
            throw new IllegalStateException("Invalid handshake challenge.");
        }
        String serverChallenge = parts[0];
        String providedSessionNonce = parts[1];

        boolean proposeSessionNonce = providedSessionNonce.isBlank();
        String sessionNonce = proposeSessionNonce ? generateSessionNonce() : providedSessionNonce;
        String clientNonce = generateSessionNonce();
        String proof = CryptoUtils.hmacSha256Base64(config.accessKey(), "client|" + serverChallenge + "|" + clientNonce);
        String payload = proof + "|" + (proposeSessionNonce ? sessionNonce : "") + "|" + clientNonce + "|" + hasChatEncryption();
        writeToServer(new Message(payload, 0, StatusCodes.HANDSHAKE_RESPONSE), true);

        Message response = (Message) inputStream.readObject();
        if (!StatusCodes.HANDSHAKE_OK.equals(response.status())) {
            throw new IllegalStateException(response.message());
        }
        String[] responseParts = response.message().split("\\|", -1);
        if (responseParts.length < 2) {
            throw new IllegalStateException("Invalid handshake confirmation.");
        }
        sessionNonce = responseParts[0];
        if (!CryptoUtils.matchesBase64HmacSha256(config.accessKey(), "server|" + serverChallenge + "|" + clientNonce + "|" + sessionNonce, responseParts[1])) {
            throw new IllegalStateException("Server proof mismatch.");
        }
        symmetric = new SymmetricEncryption(resolveTransportSecret(), sessionNonce);
    }

    private String resolveTransportSecret() {
        if (config.roomSecret() != null && !config.roomSecret().isBlank()) {
            return config.roomSecret();
        }
        return config.accessKey();
    }

    private boolean hasChatEncryption() {
        String transportSecret = resolveTransportSecret();
        return transportSecret != null && !transportSecret.isBlank();
    }

    private String decryptUserName(String encryptedName, int clientId, String fallbackName) {
        try {
            String decryptedUser = symmetric.decryptMessage(encryptedName);
            if (decryptedUser.isBlank()) {
                throw new IllegalStateException("User name is blank.");
            }
            return decryptedUser;
        } catch (Exception e) {
            Logger.warn(ClientLogMessages.couldNotDecryptUserName(clientId));
            return fallbackName;
        }
    }

    private void writeToServer(Object payload, boolean forceReset) throws IOException {
        synchronized (writeLock) {
            outputStream.writeUnshared(payload);
            outputStream.flush();
            writesSinceReset++;
            if (forceReset || writesSinceReset >= RESET_INTERVAL) {
                outputStream.reset();
                writesSinceReset = 0;
            }
        }
    }

    private String generateSessionNonce() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
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
            if (clazz.isArray()) {
                long arrayLength = info.arrayLength();
                if (arrayLength > MAX_ARRAY_LENGTH) {
                    return ObjectInputFilter.Status.REJECTED;
                }
                if (clazz == long[].class) {
                    return ObjectInputFilter.Status.ALLOWED;
                }
                Class<?> componentType = clazz.componentType();
                if (componentType == Map.Entry.class) {
                    return ObjectInputFilter.Status.ALLOWED;
                }
                return ObjectInputFilter.Status.REJECTED;
            }
            if (clazz == Message.class
                    || clazz == FilePacket.class
                    || clazz == String.class
                    || clazz == Integer.class
                    || clazz == Number.class
                    || clazz == Long.class
                    || clazz == java.util.HashMap.class) {
                return ObjectInputFilter.Status.ALLOWED;
            }
            return ObjectInputFilter.Status.REJECTED;
        };
    }
}
