package thatsapp.client.messages;

public final class ClientLogMessages {

    // DataEncryptor (local settings persistence/decryption)
    public static final String ERROR_WHILE_SAVING_SETTINGS_PREFIX = "Error while saving settings: ";
    public static final String ERROR_READING_SETTINGS_FILE_PREFIX = "Error reading settings file: ";
    public static final String ERROR_WHILE_DELETING_FILE_PREFIX = "Error while deleting file: ";
    public static final String CORRUPTED_SETTINGS_FILE = "Corrupted settings file.";
    public static final String UNSUPPORTED_SETTINGS_FORMAT = "Unsupported settings format.";
    public static final String DECRYPTION_FAILED_WRONG_PIN_OR_CORRUPTED_FILE = "Decryption failed: Wrong PIN or corrupted file.";
    // JavaFX UI controllers (window/dialog loading + message rendering)
    public static final String ERROR_OPENING_MAIN_WINDOW_PREFIX = "Error opening main window: ";
    public static final String ERROR_LOADING_MESSAGE_LAYOUT_PREFIX = "Error loading message layout: ";
    public static final String ERROR_OPENING_LOGIN_WINDOW_PREFIX = "Error opening login window: ";
    public static final String ERROR_OPENING_FILE_HANDLING_WINDOW_PREFIX = "Error opening file handling window: ";
    public static final String ERROR_OPENING_CONFIGURATION_WINDOW_PREFIX = "Error opening configuration window: ";
    public static final String ERROR_OPENING_CHANGE_WINDOW_PREFIX = "Error opening change window: ";
    public static final String ERROR_OPENING_BACKGROUND_WINDOW_PREFIX = "Error opening background window: ";
    public static final String ERROR_OPENING_ATTENTION_WINDOW_PREFIX = "Error opening attention window: ";
    // Web transport (HTTP/SSE communication)
    public static final String WEBSERVER_CONNECTION_FAILED_PREFIX = "Webserver connection failed: ";
    public static final String HTTP_REQUEST_FAILED_PREFIX = "HTTP request failed: ";
    // File transfer (web + desktop)
    public static final String UPLOAD_FAILED_PREFIX = "Upload failed: ";
    public static final String DOWNLOAD_FAILED_PREFIX = "Download failed: ";
    public static final String ERROR_CANCELLING_CONNECTION_ATTEMPT_PREFIX = "Error canceling connection attempt: ";
    // Desktop socket transport
    public static final String ERROR_SENDING_MESSAGE_PREFIX = "Error sending message: ";
    public static final String ERROR_SENDING_TYPING_STATUS_PREFIX = "Error sending typing status: ";
    public static final String ERROR_DURING_COMMUNICATION_PREFIX = "Error during communication: ";
    public static final String ERROR_HANDLING_MESSAGE_PREFIX = "Error handling message: ";
    public static final String ERROR_CLOSING_CONNECTION_PREFIX = "Error closing connection: ";
    // Desktop file-transfer service
    public static final String COULD_NOT_START_DOWNLOAD_PREFIX = "Could not start download: ";
    public static final String COULD_NOT_REQUEST_DOWNLOAD_PREFIX = "Could not request download: ";
    // Desktop socket transport connection lifecycle
    public static final String CONNECTION_FAILED = "Connection failed";
    public static final String CONNECTION_CANCELED = "Connection canceled";
    public static final String COULD_NOT_ESTABLISH_CONNECTION = "Could not establish a connection.";
    public static final String DISCONNECTED = "Disconnected";
    public static final String CONNECTION_LOST = "Connection lost";
    public static final String SERVER_REFUSED_ACCESS_KEY = "Server rejected the access key.";
    public static final String DISCONNECTION_FAILED_RESTART_APPLICATION = "Disconnection failed.\nPlease restart the application.";
    public static final String CONNECTION_CANCELED_BY_USER = "Connection canceled by the user.";
    public static final String RECONNECTION_TIMED_OUT = "Reconnection timed out";
    // Login + configuration dialogs
    public static final String SETTINGS_DATA_RESET_BY_USER = "Saved data reset by the user.";
    public static final String LOGIN_FAILED_PASSWORD_EMPTY = "Login failed: Password empty.";
    public static final String LOGIN_SUCCESSFUL = "Login successful.";
    public static final String LOGIN_FAILED_WRONG_PASSWORD_OR_CORRUPTED_FILE = "Login failed: Wrong password or corrupted file.";
    // Desktop socket transport progress logging
    public static final String ATTEMPTING_TO_CONNECT_TO_THE_SERVER = "Attempting to connect to the server...";
    public static final String CONNECTION_ESTABLISHED = "Connection established.";
    public static final String CONNECTION_FAILED_RETRYING = "Connection failed. Retrying...";
    public static final String CLOSING_CONNECTION = "Closing connection...";
    public static final String CONNECTION_CLOSED_SUCCESSFULLY = "Connection closed successfully.";
    // Desktop/web file-transfer lifecycle
    public static final String CLEARED_ALL_TRANSFERS_DISCONNECT = "Cleared all transfers (disconnect).";

    private ClientLogMessages() {
    }

    // Shared helper for "...: <exception message>" patterns
    public static String withCause(String prefix, Throwable throwable) {
        return prefix + throwable.getMessage();
    }

    public static String passwordValidationFailed(String message) {
        return "Password validation failed: " + normalizeMultiline(message);
    }

    public static String connectedAs(String ownName) {
        return "Connected as " + ownName;
    }

    public static String configurationValidationFailed(String message) {
        return "Configuration validation failed: " + normalizeMultiline(message);
    }

    public static String appliedDesktopConfiguration(String host, int port) {
        return "Applied desktopserver settings: Host " + host + ", port " + port;
    }

    public static String appliedWebConfiguration(String url, String sessionName, boolean autoCreate) {
        return "Applied webserver settings: URL " + url + ", session " + sessionName + ", auto-create=" + autoCreate;
    }

    public static String downloadFolderChosen(String path) {
        return "Download folder selected: " + path;
    }

    public static String fileHandlingSaved(String path, boolean autoDownload) {
        return "File handling settings saved: Path=" + path + ", auto-download=" + autoDownload;
    }

    public static String userJoinedChat(String name) {
        return name + " joined the chat";
    }

    public static String userLeftChat(String name) {
        return name + " left the chat";
    }

    public static String connectingUsing(String connectionType) {
        return "Connecting using " + connectionType + "...";
    }

    public static String rejectedFile(String fileName, long size, long fileSizeBytesLimit) {
        return "Rejected file " + fileName + " (" + size + " bytes), limit " + fileSizeBytesLimit + " bytes";
    }

    public static String queuedFilesForUpload(int count) {
        return "Queued " + count + " file(s) for upload.";
    }

    public static String declinedDownloadOffer(String displayName, long fileSize, long fileSizeBytesLimit) {
        return "Declined download offer for " + displayName + " (" + fileSize + " bytes), limit is " + fileSizeBytesLimit + " bytes";
    }

    public static String incomingFileOffer(String senderName, String displayName, long fileSize) {
        return "Incoming file offer from " + senderName + ": " + displayName + " (" + fileSize + " bytes)";
    }

    public static String awaitingUserConfirmationToDownload(String displayName, String senderName) {
        return "Awaiting user confirmation to download " + displayName + " from " + senderName;
    }

    public static String queuedDownload(String displayName) {
        return "Queued download: " + displayName + " (waiting for slot)";
    }

    public static String downloadCanceledFor(String displayName, String reason) {
        return "Download canceled for " + displayName + " (reason: " + reason + ")";
    }

    public static String rejectedFileOffer(String displayName, long fileSize, long fileSizeBytesLimit) {
        return "Rejected file offer " + displayName + " (" + fileSize + " bytes), limit " + fileSizeBytesLimit;
    }

    public static String couldNotDecryptFileName(String fileId) {
        return "Could not decrypt file name for file " + fileId + ". Using fallback name.";
    }

    public static String uploadStarted(String fileName, long fileSize) {
        return "Upload started: " + fileName + " (" + fileSize + " bytes)";
    }

    public static String uploadFinished(String fileName) {
        return "Upload finished: " + fileName;
    }

    public static String uploadCanceled(String fileName, String reason) {
        return "Upload canceled: " + fileName + " (" + reason + ")";
    }

    // UploadTask (desktop file-transfer)
    public static String uploadFailedForFile(String fileName, Throwable throwable) {
        return "Upload failed for " + fileName + ": " + throwable.getMessage();
    }

    public static String downloadStarted(String fileNameDisplay, String senderName, long fileSize) {
        return "Download started: " + fileNameDisplay + " from " + senderName + " (" + fileSize + " bytes)";
    }

    public static String downloadSkippedChunk(String fileNameDisplay) {
        return "Download skipped chunk for " + fileNameDisplay + ": Executor rejected task";
    }

    // DownloadTask (desktop file-transfer protocol validation)
    public static String invalidChunkOrder(String fileNameDisplay, int chunkIndex, int totalChunks, int expectedChunkIndex) {
        return "Invalid chunk order for " + fileNameDisplay + ": Got " + chunkIndex + "/" + totalChunks + ", expected " + expectedChunkIndex;
    }

    // DownloadTask (desktop file-transfer size guard)
    public static String receivedTooMuchData(String fileNameDisplay, long receivedBytes, long expectedBytes) {
        return "Received too much data for " + fileNameDisplay + ": " + receivedBytes + " > " + expectedBytes;
    }

    // DownloadTask (desktop file-transfer runtime failure)
    public static String downloadFailedForFile(String fileNameDisplay, Throwable throwable) {
        return "Download failed for " + fileNameDisplay + ": " + throwable.getMessage();
    }

    public static String downloadFinished(String fileNameDisplay) {
        return "Download finished: " + fileNameDisplay;
    }

    public static String downloadCanceled(String fileNameDisplay, String reason) {
        return "Download canceled: " + fileNameDisplay + " (" + reason + ")";
    }

    public static String couldNotProcessTypingStatus(int clientId) {
        return "Could not process typing status for client " + clientId + ". Ignoring event.";
    }

    public static String couldNotDecryptUserName(int clientId) {
        return "Could not decrypt user name for client " + clientId + ". Using fallback name.";
    }

    public static String couldNotLoadImageForBackground(String path) {
        return "Could not load background image: " + path;
    }

    public static String failedToLoadImage(String source, Throwable throwable) {
        return "Failed to load image: " + source + " (" + throwable.getMessage() + ")";
    }

    private static String normalizeMultiline(String message) {
        return message.replace("\n", " ").trim();
    }
}
