package thatsapp.client.messages;

public final class ClientAttentionMessages {

    // Main view / connection dialog
    public static final String PLEASE_ENTER_NAME = "Please enter a name.";
    // Login + change-password modal
    public static final String PLEASE_ENTER_PASSWORD = "Please enter a password.";
    public static final String PLEASE_REPEAT_PASSWORD = "Please repeat the password.";
    public static final String PASSWORDS_DO_NOT_MATCH = "Passwords do not match.";
    public static final String WRONG_PASSWORD_OR_FILE_CORRUPTED = "Wrong password or corrupted file.";
    public static final String RESET_ALL_DATA_CONFIRMATION = "Are you sure you want to reset all data?";
    // Background dialog
    public static final String PLEASE_SELECT_IMAGE_FILE = "Please select an image file.";
    public static final String PLEASE_SELECT_EXISTING_IMAGE_FILE = "Please select a readable image file.";
    // Config dialog (desktop connection)
    public static final String HOST_IP_CANNOT_BE_EMPTY = "Host IP cannot be empty.";
    public static final String INVALID_IP_ADDRESS = "Invalid IP address.";
    public static final String PORT_CANNOT_BE_EMPTY = "Port cannot be empty.";
    public static final String PORT_MUST_BE_BETWEEN_1_AND_65535 = "Port must be a number between 1 and 65535.";
    public static final String PORT_MUST_BE_A_VALID_NUMBER = "Port must be a valid number.";
    public static final String ROOM_SECRET_MIN_16_WHEN_PROVIDED = "Room secret must be at least 16\ncharacters long when provided.";
    // Config dialog (web connection)
    public static final String URL_CANNOT_BE_EMPTY = "URL cannot be empty.";
    public static final String INVALID_URL = "Invalid URL.";
    public static final String SESSION_NAME_CANNOT_BE_EMPTY = "Session name cannot be empty.";
    public static final String SESSION_NAME_MUST_NOT_CONTAIN_SLASH = "Session name must not contain '/'.";
    public static final String SESSION_NAME_MAX_64 = "Session name must be at most 64 characters long.";
    // File-transfer flow (shown through FxFileTransferUi -> attention modal)
    public static final String TOTAL_SELECTION_SIZE_LIMIT = "Total selection size must not exceed 2 GB.";
    public static final String MAX_FILES_LIMIT = "At most 10 files can be selected at once.";
    public static final String FILE_TRANSFER_DISABLED = "File transfer is disabled for this session.";

    private ClientAttentionMessages() {
    }

    // File-transfer flow: size limit per selected/offered file
    public static String fileExceedsLimit(long maxFileSizeMb) {
        return "File exceeds limit of " + maxFileSizeMb + " MB.";
    }
}
