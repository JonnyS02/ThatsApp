package thatsapp.client.communication;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import thatsapp.client.communication.filetransfer.api.FileTransferService;
import thatsapp.client.communication.filetransfer.desktop.ProtocolFileTransferService;
import thatsapp.client.communication.filetransfer.web.HttpFileTransferService;
import thatsapp.client.communication.transport.CommunicationClient;
import thatsapp.client.communication.transport.CommunicationClientListener;
import thatsapp.client.communication.transport.Config;
import thatsapp.client.communication.transport.desktop.DesktopserverTransport;
import thatsapp.client.communication.transport.web.WebserverTransport;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.FilePacket;
import thatsapp.client.data.DataHolder;
import thatsapp.common.Logger;
import thatsapp.client.ui.FxFileTransferUi;
import thatsapp.client.ui.controllers.MainController;
import javafx.util.Duration;
import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class CommunicationFacade {

    private static final long RECONNECT_WINDOW_MS = 180_000L;
    private static final long RECONNECT_RETRY_DELAY_MS = 1_000L;

    private final MainController controller;
    private CommunicationClient client;
    private final Map<Integer, String> users = new HashMap<>();
    private Integer ownId;
    private FileTransferService fileTransferManager;
    private long fileSizeBytesLimit = 0L;
    private int messageCharacterLimit = 500;
    private boolean fileTransferEnabled = true;
    private final Map<Integer, Long> typingUsers = new HashMap<>();
    private String defaultHeadline = "";
    private volatile boolean reconnecting = false;
    private volatile boolean reconnectAttemptInFlight = false;
    private volatile long reconnectDeadlineMs = 0L;
    private volatile boolean preserveHistoryOnFailure = false;
    private final Object reconnectLock = new Object();

    private final CommunicationClientListener clientListener = new CommunicationClientListener() {
        @Override
        public void onConnected(int ownId, Map<Integer, String> users, long messageCharacterLimit, long fileSizeBytesLimit, boolean fileTransferEnabled) {
            markReconnectSuccess();
            setOwnId(ownId);
            CommunicationFacade.this.users.clear();
            CommunicationFacade.this.users.put(0, "Server");
            String ownName = users.get(ownId);
            insertUser(ownId, ownName);
            for (Map.Entry<Integer, String> entry : users.entrySet()) {
                if (entry.getKey() == 0 || entry.getKey() == ownId) {
                    continue;
                }
                insertUser(entry.getKey(), entry.getValue());
            }
            applyServerRules(messageCharacterLimit, fileSizeBytesLimit, fileTransferEnabled);
            connected();
        }

        @Override
        public void onConnectionFailed(String reason) {
            DataHolder.connectionErrorMessage = reason == null ? "" : reason;
            if (reconnecting) {
                markReconnectFailure();
                return;
            }
            notConnected(preserveHistoryOnFailure);
        }

        @Override
        public void onDisconnected() {
            if (reconnecting) {
                markReconnectFailure();
                return;
            }
            boolean autoReconnect = shouldAutoReconnect();
            disconnected();
            if (autoReconnect) {
                startReconnectLoop();
            }
        }

        @Override
        public void onDisconnectionFailed() {
            notDisconnected();
        }

        @Override
        public void onTextMessage(int senderId, String message) {
            String sender = users.get(senderId);
            appendMessage(message, sender);
        }

        @Override
        public void onServerMessage(String message) {
            appendMessage(message, "Server");
        }

        @Override
        public void onUserJoined(int userId, String userName) {
            registerUser(userName, userId);
            updateUserLabel();
        }

        @Override
        public void onUserLeft(int userId) {
            removeUser(userId);
            updateUserLabel();
        }

        @Override
        public void onTyping(int senderId, long expiresAt) {
            handleTyping(senderId, expiresAt);
        }

        @Override
        public void onFilePacket(FilePacket packet) {
            fileTransferManager.handleIncoming(packet);
        }

        @Override
        public void onShutdownRequested(int code) {
            System.exit(code);
        }
    };

    public CommunicationFacade(MainController controller) {
        this(controller, null, true);
    }

    CommunicationFacade(MainController controller, FileTransferService fileTransferManager, boolean startTypingCleanup) {
        this.controller = controller;
        users.put(0, "Server");
        this.fileTransferManager = fileTransferManager != null
                ? fileTransferManager
                : new ProtocolFileTransferService(() -> client, new FxFileTransferUi(controller), users::get);
        this.fileTransferManager.setFileSizeBytesLimit(fileSizeBytesLimit);
        this.fileTransferManager.setFileTransferEnabled(fileTransferEnabled);
        if (startTypingCleanup) {
            Timeline typingCleanupTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> cleanupTypingUsers()));
            typingCleanupTimeline.setCycleCount(Timeline.INDEFINITE);
            Platform.runLater(typingCleanupTimeline::play);
        }
    }

    public void connecting(String connectionType) {
        startConnection(connectionType, true, true);
    }

    public void connected() {
        DataHolder.isConnecting = false;
        controller.setFileTransferEnabled(fileTransferEnabled);
        controller.setInputEnabled(true);
        toggleConnectButton(true, "Disconnect");
        controller.hideSpinner();
        updateUserLabel();
        String ownName = users.get(ownId);
        String connectedMsg = ClientLogMessages.connectedAs(ownName);
        Logger.info(connectedMsg);
        controller.appendMessage(connectedMsg, "system.fxml", "");
        DataHolder.isConnected = true;
        controller.updateConnectionStatusIcon(true);
    }

    public void disconnecting() {
        stopReconnect();
        DataHolder.isConnecting = false;
        controller.setConnectButtonDisabled(true);
        controller.setInputEnabled(false);
        controller.showSpinner();
        controller.scrollDown();
        CommunicationClient toDisconnect = client;
        if (toDisconnect != null) {
            Thread.startVirtualThread(toDisconnect::disconnect);
        }
        fileTransferManager.cancelAll();
    }

    public void disconnected() {
        toggleConnectButton(false, "Connect");
        controller.setConnectionControlsEnabled(true);
        controller.setInputEnabled(false);
        fileTransferEnabled = false;
        fileTransferManager.setFileTransferEnabled(false);
        controller.setFileTransferEnabled(false);
        controller.hideSpinner();
        if (DataHolder.closingStatus == 1) {
            Logger.info(ClientLogMessages.DISCONNECTED);
            controller.appendMessage(ClientLogMessages.DISCONNECTED, "system.fxml", "");
        } else if (DataHolder.closingStatus == 0) {
            Logger.warn(ClientLogMessages.CONNECTION_LOST);
            controller.appendMessage(ClientLogMessages.CONNECTION_LOST, "system.fxml", "");
        } else if (DataHolder.closingStatus == -1) {
            Logger.warn(ClientLogMessages.SERVER_REFUSED_ACCESS_KEY);
            controller.appendMessage(ClientLogMessages.SERVER_REFUSED_ACCESS_KEY, "system.fxml", "");
        }
        DataHolder.closingStatus = 0;
        DataHolder.isConnected = false;
        DataHolder.isConnecting = false;
        controller.setHeadlineText("");
        fileTransferManager.cancelAll();
        controller.updateConnectionStatusIcon(false);
        clearTypingState();
    }

    public void notDisconnected() {
        toggleConnectButton(true, "Disconnect");
        controller.setFileTransferEnabled(fileTransferEnabled);
        controller.setInputEnabled(true);
        controller.clearMessageNodes();
        String text = ClientLogMessages.DISCONNECTION_FAILED_RESTART_APPLICATION;
        Logger.warn(text);
        controller.appendMessage(text, "system.fxml", "");
    }

    public void updateUserLabel() {
        StringBuilder labelText = new StringBuilder();
        for (String user : users.values()) {
            if (!user.equals("Server")) {
                labelText.append(user).append(", ");
            }
        }
        if (labelText.length() >= 2) {
            defaultHeadline = labelText.substring(0, labelText.length() - 2);
        } else {
            defaultHeadline = "";
        }
        renderHeadline();
    }

    public void appendMessage(String message, String user) {
        controller.appendMessage(message, "otherText.fxml", user);
    }

    public void sendMessage(String message) {
        if (client != null) {
            client.sendMessage(message);
        }
    }

    public void sendTypingPing(long ttlMs) {
        if (client != null && DataHolder.isConnected) {
            client.sendTyping(ttlMs);
        }
    }

    public void sendTypingStopped() {
        sendTypingPing(0);
    }

    public void sendFiles(java.util.List<File> files) {
        fileTransferManager.enqueueUploads(files);
    }

    public void applyServerRules(long messageCharacterLimit, long fileSizeBytesLimit, boolean fileTransferEnabled) {
        this.messageCharacterLimit = (int) messageCharacterLimit;
        this.fileSizeBytesLimit = fileSizeBytesLimit;
        this.fileTransferEnabled = fileTransferEnabled;
        fileTransferManager.setFileSizeBytesLimit(fileSizeBytesLimit);
        fileTransferManager.setFileTransferEnabled(fileTransferEnabled);
        Platform.runLater(() -> {
            controller.setMessageCharacterLimit(this.messageCharacterLimit);
            controller.setFileTransferEnabled(this.fileTransferEnabled);
        });
    }

    public void setOwnId(Integer ownId) {
        this.ownId = ownId;
    }

    public void registerUser(String name, int id) {
        insertUser(id, name);
        String newName = users.get(id);
        Platform.runLater(() -> {
            appendMessage("Joined the chat", newName);
            Logger.info(ClientLogMessages.userJoinedChat(newName));
        });
    }

    public void removeUser(int id) {
        String name = users.get(id);
        users.remove(id);
        typingUsers.remove(id);
        Platform.runLater(() -> {
            appendMessage("Left the chat", name);
            Logger.info(ClientLogMessages.userLeftChat(name));
            renderHeadline();
        });
    }

    public void cancelConnecting() {
        stopReconnect();
        DataHolder.connectionErrorMessage = ClientLogMessages.CONNECTION_CANCELED;
        if (client != null) {
            client.requestCancel();
        }
        Logger.warn(ClientLogMessages.CONNECTION_CANCELED_BY_USER);
    }

    public void handleTyping(int senderId, long expiresAt) {
        if (ownId != null && senderId == ownId) {
            return;
        }
        if (expiresAt <= 0) {
            typingUsers.remove(senderId);
        } else {
            typingUsers.put(senderId, expiresAt);
        }
        renderHeadline();
    }

    private void startConnection(String connectionType, boolean clearHistory, boolean resetTransfers) {
        DataHolder.connectionErrorMessage = "";
        if (clearHistory) {
            preserveHistoryOnFailure = false;
        }
        boolean desktopMode = DataHolder.connectionTypes[0].equals(connectionType);
        DataHolder.isConnecting = true;
        Logger.info(ClientLogMessages.connectingUsing(connectionType));
        toggleConnectButton(true, "Cancel");
        controller.setConnectionControlsEnabled(false);
        if (clearHistory) {
            controller.clearMessageNodes();
        }
        controller.showSpinner();
        clearTypingState();
        ownId = null;
        users.clear();
        users.put(0, "Server");
        int preservedWebEventId = clearHistory ? 0 : currentWebLastEventId();
        client = desktopMode ? new DesktopserverTransport() : new WebserverTransport();
        if (client instanceof WebserverTransport webTransport) {
            webTransport.setLastEventId(preservedWebEventId);
        }
        if (resetTransfers) {
            selectFileTransferManager(desktopMode);
        }
        Config config = new Config(
                desktopMode ? DataHolder.hostIp : DataHolder.url,
                desktopMode ? DataHolder.port : 0,
                DataHolder.name,
                desktopMode ? DataHolder.desktopserverAccessKey : DataHolder.webserverAccessKey,
                desktopMode ? DataHolder.desktopserverRoomSecret : DataHolder.webserverRoomSecret,
                desktopMode ? "" : DataHolder.sessionName,
                !desktopMode && DataHolder.autoCreate
        );
        client.connect(config, clientListener, Platform::runLater);
    }

    private void notConnected(boolean preserveHistory) {
        toggleConnectButton(false, "Connect");
        controller.setConnectionControlsEnabled(true);
        controller.hideSpinner();
        fileTransferEnabled = false;
        fileTransferManager.setFileTransferEnabled(false);
        controller.setFileTransferEnabled(false);
        if (!preserveHistory) {
            controller.clearMessageNodes();
        }
        String message = DataHolder.connectionErrorMessage.isBlank() ? ClientLogMessages.CONNECTION_FAILED : DataHolder.connectionErrorMessage;
        Logger.warn(message);
        controller.appendMessage(message, "system.fxml", "");
        DataHolder.connectionErrorMessage = "";
        DataHolder.isConnected = false;
        DataHolder.isConnecting = false;
        fileTransferManager.cancelAll();
        controller.updateConnectionStatusIcon(false);
    }

    private boolean shouldAutoReconnect() {
        return client instanceof WebserverTransport && DataHolder.closingStatus == 0;
    }

    private void startReconnectLoop() {
        if (reconnecting) {
            return;
        }
        reconnecting = true;
        preserveHistoryOnFailure = true;
        reconnectDeadlineMs = System.currentTimeMillis() + RECONNECT_WINDOW_MS;
        Thread.startVirtualThread(this::runReconnectLoop);
    }

    private void runReconnectLoop() {
        while (reconnecting) {
            long now = System.currentTimeMillis();
            if (now >= reconnectDeadlineMs) {
                awaitReconnectAttempt();
                if (!reconnecting) {
                    return;
                }
                DataHolder.connectionErrorMessage = ClientLogMessages.RECONNECTION_TIMED_OUT;
                reconnecting = false;
                Platform.runLater(() -> notConnected(true));
                return;
            }
            startReconnectAttempt();
            awaitReconnectAttempt();
            if (!reconnecting) {
                return;
            }
            try {
                Thread.sleep(RECONNECT_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void startReconnectAttempt() {
        synchronized (reconnectLock) {
            if (reconnectAttemptInFlight || !reconnecting) {
                return;
            }
            reconnectAttemptInFlight = true;
        }
        Platform.runLater(() -> startConnection(DataHolder.connectionType, false, false));
    }

    private void awaitReconnectAttempt() {
        synchronized (reconnectLock) {
            while (reconnecting && reconnectAttemptInFlight) {
                long remaining = reconnectDeadlineMs - System.currentTimeMillis();
                long waitMs = remaining > 0 ? Math.min(remaining, RECONNECT_RETRY_DELAY_MS) : RECONNECT_RETRY_DELAY_MS;
                try {
                    reconnectLock.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void markReconnectSuccess() {
        synchronized (reconnectLock) {
            if (!reconnecting) {
                return;
            }
            reconnecting = false;
            preserveHistoryOnFailure = false;
            reconnectAttemptInFlight = false;
            reconnectLock.notifyAll();
        }
    }

    private void markReconnectFailure() {
        synchronized (reconnectLock) {
            reconnectAttemptInFlight = false;
            reconnectLock.notifyAll();
        }
    }

    private void stopReconnect() {
        synchronized (reconnectLock) {
            reconnecting = false;
            reconnectAttemptInFlight = false;
            reconnectLock.notifyAll();
        }
    }

    private void insertUser(int id, String name) {
        String baseName = name;
        int counter = 1;
        while (users.containsValue(name)) {
            name = baseName + " (" + counter + ")";
            counter++;
        }
        users.put(id, name);
    }

    private void toggleConnectButton(boolean isDanger, String text) {
        controller.setConnectButtonState(isDanger, text);
    }

    private void cleanupTypingUsers() {
        boolean removed = false;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Long>> iterator = typingUsers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                iterator.remove();
                removed = true;
            }
        }
        if (removed) {
            renderHeadline();
        }
    }

    private void renderHeadline() {
        Platform.runLater(() -> {
            cleanupExpiredIfNeeded();
            int count = typingUsers.size();
            if (count == 0) {
                controller.setHeadlineText(defaultHeadline);
                return;
            }
            if (count == 1) {
                Integer userId = typingUsers.keySet().iterator().next();
                String name = users.get(userId);
                controller.setHeadlineText(name + " is typing...");
            } else {
                controller.setHeadlineText(count + " users are typing...");
            }
        });
    }

    private void cleanupExpiredIfNeeded() {
        long now = System.currentTimeMillis();
        typingUsers.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private void clearTypingState() {
        typingUsers.clear();
        defaultHeadline = "";
        controller.setHeadlineText("");
    }

    private int currentWebLastEventId() {
        if (client instanceof WebserverTransport webTransport) {
            return webTransport.lastEventId();
        }
        return 0;
    }

    private void selectFileTransferManager(boolean desktopMode) {
        fileTransferManager.cancelAll();
        if (desktopMode) {
            fileTransferManager = new ProtocolFileTransferService(() -> client, new FxFileTransferUi(controller), users::get);
        } else {
            fileTransferManager = new HttpFileTransferService(() -> (WebserverTransport) client, new FxFileTransferUi(controller), users::get);
        }
        fileTransferManager.setFileSizeBytesLimit(fileSizeBytesLimit);
        fileTransferManager.setFileTransferEnabled(fileTransferEnabled);
    }
}
