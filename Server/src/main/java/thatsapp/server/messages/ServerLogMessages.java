package thatsapp.server.messages;

public final class ServerLogMessages {

    // UI controllers (main/settings/attention windows)
    public static final String ERROR_OPENING_SETTINGS_WINDOW_PREFIX = "Error opening settings window: ";
    public static final String ERROR_OPENING_MAIN_WINDOW_PREFIX = "Error opening main window: ";
    public static final String ERROR_GETTING_EXTERNAL_IP_PREFIX = "Error getting external IP: ";
    public static final String ERROR_GETTING_INTERNAL_IP_PREFIX = "Error getting internal IP: ";
    public static final String ERROR_OPENING_ATTENTION_WINDOW_PREFIX = "Error opening attention window: ";
    // ServerSettingsStore (persistence + crypto)
    public static final String ERROR_SAVING_SERVER_SETTINGS_PREFIX = "Error while saving server settings: ";
    public static final String ERROR_READING_SERVER_SETTINGS_FILE_PREFIX = "Error reading server settings file: ";
    public static final String ERROR_DECRYPTING_SERVER_SETTINGS_PREFIX = "Error decrypting server settings: ";
    public static final String ERROR_SERIALIZING_SERVER_SETTINGS_PREFIX = "Error serializing server settings: ";
    public static final String SERVER_SETTINGS_FILE_UNKNOWN_FORMAT = "Server settings file has an unsupported format.";
    // Server lifecycle / socket accept loop
    public static final String ERROR_ACCEPTING_CONNECTION_PREFIX = "Error accepting connection: ";
    public static final String ERROR_STARTING_SERVER_PREFIX = "Error starting server: ";
    public static final String ERROR_STOPPING_SERVER_PREFIX = "Error stopping server: ";
    public static final String STOP_REQUESTED_BUT_SERVER_IS_NOT_RUNNING = "Stop requested, but the server is not running.";
    public static final String SHUTDOWN_REQUESTED_SENDING_SHUTDOWN_COMMAND = "Shutdown requested. Sending shutdown command to clients.";
    public static final String SKIPPING_SETTINGS_SAVE_BECAUSE_PORT_IS_INVALID = "Skipping settings save because port is invalid.";
    // FileTransferManager (temp storage and replay)
    public static final String ERROR_CREATING_TEMP_DIR_PREFIX = "Could not create temp dir: ";
    public static final String ERROR_BUFFERING_FILE_PREFIX = "Error while buffering file: ";
    public static final String ERROR_REPLAYING_BUFFERED_FILE_PREFIX = "Error replaying buffered file: ";
    public static final String ERROR_DELETING_TEMP_FILE_PREFIX = "Error deleting temp file: ";
    public static final String ERROR_CLEANING_TEMP_DIR_PREFIX = "Error cleaning temp dir: ";
    // ClientHandler (client communication + handshake)
    public static final String ERROR_PROCESSING_CLIENT_MESSAGES_PREFIX = "Error processing client messages: ";
    public static final String ERROR_SENDING_MESSAGE_TO_CLIENT_PREFIX = "Error sending message to client: ";
    public static final String ERROR_SENDING_CLIENT_NAME_TO_CLIENT_PREFIX = "Error sending client name to client: ";
    public static final String ERROR_SENDING_FILE_PACKET_TO_CLIENT_PREFIX = "Error sending file packet to client: ";
    public static final String ERROR_REMOVING_CLIENT_NAME_PREFIX = "Error removing client name from client: ";
    public static final String ERROR_CLOSING_CLIENT_CONNECTION_PREFIX = "Error closing client connection: ";
    public static final String HANDSHAKE_FAILED_PREFIX = "Handshake failed: ";

    private ServerLogMessages() {
    }

    // Shared helper for "...: <exception message>" patterns
    public static String withCause(String prefix, Throwable throwable) {
        return prefix + throwable.getMessage();
    }

    public static String settingsValidationFailed(String message) {
        return "Settings validation failed: " + normalizeMultiline(message);
    }

    public static String serverStartedOnPort(String port) {
        return "Server started on port " + port + ".";
    }

    public static String serverStopped() {
        return "Server stopped.";
    }

    public static String incomingConnectionFrom(Object address) {
        return "Incoming connection from " + address;
    }

    public static String clientDisconnected(int clientId) {
        return "Client " + clientId + " has disconnected.";
    }

    public static String clientSentInvalidRegistrationMessage(int clientId) {
        return "Client " + clientId + " sent an invalid registration message.";
    }

    public static String clientConnected(int clientId, String remoteIp) {
        return "Client #" + clientId + " connected: " + remoteIp;
    }

    public static String connectionWithClientClosed(int clientId) {
        return "Connection with client " + clientId + " closed.";
    }

    public static String clientFailedHandshake(int clientId) {
        return "Client " + clientId + " failed handshake.";
    }

    public static String clientTimedOutDuringSetup(int clientId) {
        return "Client " + clientId + " timed out before completing connection setup.";
    }

    public static String clientRejectedUserLimitReached(int clientId) {
        return "Client " + clientId + " rejected: User limit reached.";
    }

    public static String rejectedMessageFromClient(int clientId, String reason) {
        return "Rejected message from client " + clientId + ": " + reason;
    }

    public static String rejectedFilePacketMissing(int clientId) {
        return "Rejected file packet from client " + clientId + ": Packet missing";
    }

    public static String rejectedFilePacketStatusMissing(int clientId) {
        return "Rejected file packet from client " + clientId + ": Status missing";
    }

    public static String rejectedFilePacketUnknownStatus(int clientId, String status) {
        return "Rejected file packet from client " + clientId + ": Unknown status " + status;
    }

    public static String uploadCanceledUploaderDisconnected(String fileName) {
        return "Upload canceled (uploader disconnected): " + fileName;
    }

    public static String rejectedUploadInvalidFileId(int uploaderId) {
        return "Rejected upload from client " + uploaderId + ": Invalid file id";
    }

    public static String rejectedUploadInvalidFileSize(int uploaderId) {
        return "Rejected upload from client " + uploaderId + ": Invalid file size";
    }

    public static String rejectedUploadExceedsLimit(int uploaderId, String fileName, long fileSize, long maxFileSize) {
        return "Rejected upload from client " + uploaderId + ": " + fileName + " (" + fileSize + " bytes, limit " + maxFileSize + ")";
    }

    public static String rejectedUploadFileCountLimitReached(int uploaderId, String fileName, int fileCountLimit) {
        return "Rejected upload from client " + uploaderId + ": " + fileName + " (file count limit " + fileCountLimit + " reached)";
    }

    public static String rejectedUploadFileTransferDisabled(int uploaderId, String fileName) {
        return "Rejected upload from client " + uploaderId + ": " + fileName + " (file transfer disabled)";
    }

    public static String rejectedUploadInvalidChunkCount(int uploaderId, String fileName) {
        return "Rejected upload from client " + uploaderId + ": Invalid chunk count for " + fileName;
    }

    public static String uploadStartedFromClient(int uploaderId, String fileName, long fileSize) {
        return "Upload started from client " + uploaderId + ": " + fileName + " (" + fileSize + " bytes)";
    }

    public static String uploadFinishedFromClient(int uploaderId, String fileName) {
        return "Upload finished from client " + uploaderId + ": " + fileName;
    }

    public static String uploadCanceledByClient(int clientId, String fileName) {
        return "Upload canceled by client " + clientId + ": " + fileName;
    }

    public static String downloadCanceledByClient(int clientId, String fileName) {
        return "Download canceled by client " + clientId + " for file " + fileName;
    }

    public static String downloadFinishedForClient(int clientId, String fileName) {
        return "Download finished for client " + clientId + ": " + fileName;
    }

    public static String downloadStartedForClient(int clientId, String fileName, long fileSize) {
        return "Download started for client " + clientId + ": " + fileName + " (" + fileSize + " bytes)";
    }

    public static String uploadCanceledFromClient(int uploaderId, String fileName, String reason) {
        return "Upload canceled from client " + uploaderId + ": " + fileName + " (" + reason + ")";
    }

    private static String normalizeMultiline(String message) {
        return message.replace("\n", " ").trim();
    }
}
