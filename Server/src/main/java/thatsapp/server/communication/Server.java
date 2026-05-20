package thatsapp.server.communication;

import javafx.application.Platform;
import thatsapp.common.Message;
import thatsapp.common.StatusCodes;
import thatsapp.common.ServerRules;
import thatsapp.server.ui.controllers.MainController;
import thatsapp.common.Logger;
import thatsapp.server.ServerSettingsStore;
import thatsapp.server.messages.ServerLogMessages;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

public class Server {

    private final MainController controller;
    private ServerSocket serverSocket;
    private final Map<Integer, ClientHandler> clients = new ConcurrentHashMap<>();
    private final Map<Integer, String> clientNames = new ConcurrentHashMap<>();
    private final Set<Integer> admittedClients = ConcurrentHashMap.newKeySet();
    private boolean running = false;
    private String sessionNonce = "";
    private Boolean chatEncrypted = null;
    private final SecureRandom secureRandom = new SecureRandom();
    private final FileTransferManager fileTransferManager;
    private ServerRules rules = ServerRules.defaults();

    public Server(MainController controller) {
        this.controller = controller;
        this.fileTransferManager = new FileTransferManager(this);
        loadStoredRules();
    }

    public MainController getController() {
        return controller;
    }

    public Map<Integer, String> getClientNames() {
        return clientNames;
    }

    public FileTransferManager getFileTransferManager() {
        return fileTransferManager;
    }

    public synchronized ServerRules getRules() {
        return rules;
    }

    public synchronized void updateRules(ServerRules rules) {
        this.rules = rules;
    }

    public ClientHandler getClient(int clientId) {
        ClientHandler handler = clients.get(clientId);
        return handler != null && handler.isReady() ? handler : null;
    }

    public Set<Integer> getClientIds() {
        return new HashSet<>(clients.keySet());
    }

    public boolean isRunning() {
        return running;
    }

    public void start(int port, String accessKey) {
        try {
            sessionNonce = "";
            serverSocket = new ServerSocket(port);
            running = true;
            String startedMsg = ServerLogMessages.serverStartedOnPort(String.valueOf(port));
            Logger.info(startedMsg);
            notifyControllerStarted();
            new Thread(() -> {
                while (running) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        Logger.info(ServerLogMessages.incomingConnectionFrom(clientSocket.getInetAddress()));
                        int clientId = findNewClientId();
                        ClientHandler clientHandler = new ClientHandler(clientSocket, clientId, this, accessKey);
                        clients.put(clientId, clientHandler);
                        clientHandler.start();
                    } catch (IOException e) {
                        if (running) {
                            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_ACCEPTING_CONNECTION_PREFIX, e), e);
                        }
                    }
                }
            }).start();
        } catch (IOException e) {
            String message = ServerLogMessages.withCause(ServerLogMessages.ERROR_STARTING_SERVER_PREFIX, e);
            Logger.error(message, e);
            appendControllerMessage(message);
            running = false;
        }
    }

    public void stop() {
        if (!running) {
            Logger.info(ServerLogMessages.STOP_REQUESTED_BUT_SERVER_IS_NOT_RUNNING);
            return;
        }
        try {
            for (ClientHandler client : clients.values()) {
                client.closeConnection();
            }
            clients.clear();
            admittedClients.clear();
            serverSocket.close();
            running = false;
            String stoppedMsg = ServerLogMessages.serverStopped();
            Logger.info(stoppedMsg);
            notifyControllerStopped();
            sessionNonce = "";
            chatEncrypted = null;
            fileTransferManager.cleanupAll();
        } catch (IOException e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_STOPPING_SERVER_PREFIX, e), e);
        }
    }

    public void sendServerMessageToAll(String serverMessage) {
        Message message = new Message(serverMessage, 0, StatusCodes.SERVER_MESSAGE);
        for (ClientHandler client : clients.values()) {
            if (client.isReady()) {
                client.sendMessage(message);
            }
        }
    }

    public void broadcast(Message message, int senderId, String status) {
        Message broadcastMessage = new Message(message.message(), senderId, status);
        for (Map.Entry<Integer, ClientHandler> entry : clients.entrySet()) {
            ClientHandler handler = entry.getValue();
            if (entry.getKey() != senderId && handler.isReady()) {
                handler.sendMessage(broadcastMessage);
            }
        }
    }

    public void registerClient(int clientId, String clientName) {
        clientNames.put(clientId, clientName);
        updateControllerUserCounter();
        for (Map.Entry<Integer, ClientHandler> entry : clients.entrySet()) {
            ClientHandler handler = entry.getValue();
            if (entry.getKey() != clientId && handler.isReady()) {
                handler.sendClientName(clientName, clientId);
            }
        }
    }

    public void removeClient(int clientId) {
        clearClientState(clientId);
        fileTransferManager.clientDisconnected(clientId);
        String message = ServerLogMessages.clientDisconnected(clientId);
        Logger.info(message);
        appendControllerMessage(message);
        updateControllerUserCounter();
        for (Map.Entry<Integer, ClientHandler> entry : clients.entrySet()) {
            ClientHandler handler = entry.getValue();
            if (handler.isReady()) {
                handler.removeClientName(clientId);
            }
        }
    }

    public void dropClientSilently(int clientId) {
        clearClientState(clientId);
    }

    public void shutdown() {
        Logger.warn(ServerLogMessages.SHUTDOWN_REQUESTED_SENDING_SHUTDOWN_COMMAND);
        sendServerMessageToAll("-99"); // Shutdown command
        fileTransferManager.cleanupAll();
        System.exit(-99);
    }

    public synchronized String ensureSessionNonce(String proposedNonce) {
        if (sessionNonce.isBlank()) {
            sessionNonce = proposedNonce == null || proposedNonce.isBlank() ? generateSessionNonce() : proposedNonce;
        }
        return sessionNonce;
    }

    public synchronized String getSessionNonce() {
        return sessionNonce;
    }

    public synchronized boolean tryAdmitClient(int clientId) {
        if (admittedClients.contains(clientId)) {
            return true;
        }
        int userLimit = rules.userLimit();
        if (userLimit > 0 && admittedClients.size() >= userLimit) {
            return false;
        }
        admittedClients.add(clientId);
        return true;
    }

    public synchronized boolean isFileTransferEnabled() {
        return rules.fileCountLimit() >= 0;
    }

    public synchronized boolean ensureChatEncrypted(boolean proposedMode) {
        if (chatEncrypted == null) {
            chatEncrypted = proposedMode;
            return true;
        }
        return chatEncrypted == proposedMode;
    }

    public synchronized boolean isChatEncrypted() {
        return Boolean.TRUE.equals(chatEncrypted);
    }

    public String generateChallenge() {
        return UUID.randomUUID().toString();
    }

    private synchronized void releaseClientAdmission(int clientId) {
        admittedClients.remove(clientId);
    }

    private void clearClientState(int clientId) {
        clients.remove(clientId);
        releaseClientAdmission(clientId);
        clientNames.remove(clientId);
        if (clients.isEmpty()) {
            sessionNonce = "";
            chatEncrypted = null;
        }
    }

    private int findNewClientId() {
        int newClientId = 1;
        while (clients.containsKey(newClientId)) {
            newClientId++;
        }
        return newClientId;
    }

    private String generateSessionNonce() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void loadStoredRules() {
        ServerRules stored = ServerSettingsStore.load();
        if (stored != null) {
            this.rules = stored;
        }
    }

    private void notifyControllerStarted() {
        if (controller == null) {
            return;
        }
        controller.started();
    }

    private void notifyControllerStopped() {
        if (controller == null) {
            return;
        }
        controller.stopped();
    }

    private void appendControllerMessage(String message) {
        if (controller == null) {
            return;
        }
        Platform.runLater(() -> controller.appendMessage(message));
    }

    private void updateControllerUserCounter() {
        if (controller == null) {
            return;
        }
        Platform.runLater(() -> controller.updateUserCounter(clientNames));
    }
}
