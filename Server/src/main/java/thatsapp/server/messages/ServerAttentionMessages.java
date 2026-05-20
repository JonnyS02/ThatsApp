package thatsapp.server.messages;

public final class ServerAttentionMessages {

    // Settings modal
    public static final String FILE_SIZE_LIMIT_TOO_LARGE = "File size limit is too large.";
    // Main server window (start/port validation)
    public static final String PORT_MUST_NOT_BE_EMPTY = "Port cannot be empty.";
    public static final String PORT_MUST_BE_BETWEEN_0_AND_65535 = "Port must be between 0 and 65535.";
    public static final String PORT_MUST_BE_A_NUMBER = "Port must be a number.";
    private ServerAttentionMessages() {
    }

    // Settings modal (generic field validation text)
    public static String fieldCannotBeEmpty(String label) {
        return label + " cannot be empty.";
    }

    // Settings modal (generic min value validation)
    public static String fieldMustBeAtLeastZero(String label) {
        return label + " must be at least 0.";
    }

    public static String fieldMustBeAtLeastNegativeOne(String label) {
        return label + " must be at least -1.";
    }

    // Settings modal (generic numeric validation)
    public static String fieldMustBeValidNumber(String label) {
        return label + " must be a valid number.";
    }
}
