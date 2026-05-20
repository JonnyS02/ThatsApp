package thatsapp.common;

public final class StatusCodes {

    private StatusCodes() {}

    public static final String HANDSHAKE_CHALLENGE = "HANDSHAKE_CHALLENGE";
    public static final String HANDSHAKE_RESPONSE = "HANDSHAKE_RESPONSE";
    public static final String HANDSHAKE_OK = "HANDSHAKE_OK";
    public static final String HANDSHAKE_FAIL = "HANDSHAKE_FAIL";

    public static final String REGISTER = "REGISTER";
    public static final String MESSAGE = "MESSAGE";
    public static final String FILE_META = "FILE_META";
    public static final String FILE_CHUNK = "FILE_CHUNK";
    public static final String FILE_COMPLETE = "FILE_COMPLETE";
    public static final String FILE_CANCEL = "FILE_CANCEL";
    public static final String FILE_ACK = "FILE_ACK";
    public static final String FILE_REQUEST = "FILE_REQUEST";
    public static final String USER_JOIN = "USER_JOIN";
    public static final String USER_LEAVE = "USER_LEAVE";
    public static final String SERVER_MESSAGE = "SERVER_MESSAGE";
    public static final String USER_TYPING = "USER_TYPING";
}
