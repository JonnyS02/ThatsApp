package thatsapp.client.communication.transport.web;

import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.transport.CommunicationClient;
import thatsapp.client.communication.transport.CommunicationClientListener;
import thatsapp.client.communication.transport.Config;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.CryptoUtils;
import thatsapp.common.Logger;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.net.http.HttpRequest;

public final class WebserverTransport extends Thread implements CommunicationClient {

    private static final String UNKNOWN_USER_NAME = "Unknown user";
    private static final int API_CONNECT_TIMEOUT_MS = 10000;
    private static final int API_READ_TIMEOUT_MS = 30000;
    private static final int SSE_CONNECT_TIMEOUT_MS = 20000;
    private static final int SSE_READ_TIMEOUT_MS = 1000;
    private static final String TOKEN_HEADER = "X-ThatsApp-Token";
    private static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    private static final String ACK_EVENT_ID_HEADER = "X-Last-Event-ID";

    private volatile boolean cancelRequested = false;
    private volatile boolean disconnectRequested = false;

    private volatile HttpURLConnection sseConnection;
    private volatile InputStream sseStream;

    private volatile SymmetricEncryption symmetric;
    private volatile String baseUrl;
    private volatile String sessionName;
    private volatile String token;
    private volatile int ownId;
    private volatile int lastEventId;

    private Config config;
    private CommunicationClientListener listener;
    private Executor callbackExecutor;

    @Override
    public void connect(Config config, CommunicationClientListener listener, Executor callbackExecutor) {
        this.config = config;
        this.listener = listener;
        this.callbackExecutor = callbackExecutor;
        this.cancelRequested = false;
        this.disconnectRequested = false;
        start();
    }

    @Override
    public void requestCancel() {
        cancelRequested = true;
        closeSse();
    }

    @Override
    public void disconnect() {
        disconnectRequested = true;
        try {
            sendDisconnect();
        } finally {
            closeSse();
        }
    }

    @Override
    public SymmetricEncryption getSymmetric() {
        return symmetric;
    }

    @Override
    public void sendMessage(String message) {
        String encrypted = symmetric.encryptChatMessage(message);
        fireAndForgetAuthorizedPostJson(
                url("/api/sessions/" + enc(sessionName) + "/messages"),
                "{\"message\":\"" + jsonEscape(encrypted) + "\"}",
                false
        );
    }

    @Override
    public void sendTyping(long ttlMs) {
        String encrypted = symmetric.encryptMessage(String.valueOf(ttlMs));
        fireAndForgetAuthorizedPostJson(
                url("/api/sessions/" + enc(sessionName) + "/typing"),
                "{\"payload\":\"" + jsonEscape(encrypted) + "\"}",
                false
        );
    }

    @Override
    public void sendFilePacket(FilePacket packet) throws IOException {
        throw new IOException("File packets are not supported via WebserverTransport");
    }

    @Override
    public void run() {
        boolean connected = false;
        try {
            this.baseUrl = normalizeBaseUrl(config.host());
            this.sessionName = hashSessionName(config.sessionName(), config.roomSecret());

            String handshakeJson = "{\"accessKey\":\"" + jsonEscape(config.accessKey()) + "\",\"autoCreate\":" + config.autoCreate() + ",\"chatEncrypted\":" + isChatEncrypted() + "}";
            Map<String, String> handshake = parseKvLines(postJson(
                    url("/api/sessions/" + enc(sessionName) + "/handshake"),
                    handshakeJson
            ));
            String sessionNonce = urlDecode(handshake.get("sessionNonce"));
            this.symmetric = new SymmetricEncryption(config.roomSecret(), sessionNonce);

            if (cancelRequested) {
                fail("Connection canceled");
                return;
            }

            String encryptedName = symmetric.encryptMessage(config.userName());
            Map<String, String> connect = parseKvLines(postJson(
                    url("/api/sessions/" + enc(sessionName) + "/connect"),
                    "{\"accessKey\":\"" + jsonEscape(config.accessKey()) + "\",\"name\":\"" + jsonEscape(encryptedName) + "\",\"chatEncrypted\":" + isChatEncrypted() + "}"
            ));
            this.token = connect.get("token");
            this.ownId = Integer.parseInt(connect.get("clientId"));
            long messageCharacterLimit = Long.parseLong(connect.get("messageCharacterLimit"));
            long fileSizeBytesLimit = Long.parseLong(connect.get("fileSizeBytesLimit"));
            String fileTransferEnabledValue = connect.get("fileTransferEnabled");
            if (fileTransferEnabledValue == null) {
                throw new IllegalStateException("Missing file transfer flag");
            }
            boolean fileTransferEnabled = Boolean.parseBoolean(fileTransferEnabledValue);

            Map<Integer, String> users = new HashMap<>();
            String ownUserName = config.userName() == null || config.userName().isBlank() ? UNKNOWN_USER_NAME : config.userName();
            for (Map.Entry<String, String> entry : connect.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("user.")) {
                    continue;
                }
                int id = Integer.parseInt(key.substring("user.".length()));
                String fallbackName = id == ownId ? ownUserName : UNKNOWN_USER_NAME;
                users.put(id, decryptUserName(urlDecode(entry.getValue()), id, fallbackName));
            }

            if (cancelRequested) {
                fail("Connection canceled");
                return;
            }

            openSse();
            connected = true;
            callbackExecutor.execute(() -> listener.onConnected(ownId, users, messageCharacterLimit, fileSizeBytesLimit, fileTransferEnabled));

            readSseLoop();
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.WEBSERVER_CONNECTION_FAILED_PREFIX, e), e);
            if (!connected) {
                fail(e.getMessage());
            }
        } finally {
            closeSse();
            if (connected) {
                callbackExecutor.execute(listener::onDisconnected);
            }
        }
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String sessionName() {
        return sessionName;
    }

    public String token() {
        return token;
    }

    public int lastEventId() {
        return lastEventId;
    }

    public void setLastEventId(int lastEventId) {
        this.lastEventId = Math.max(0, lastEventId);
    }

    public HttpRequest.Builder authorize(HttpRequest.Builder builder) {
        return builder.header(TOKEN_HEADER, tokenHeaderValue());
    }

    public void authorize(HttpURLConnection connection) {
        connection.setRequestProperty(TOKEN_HEADER, tokenHeaderValue());
    }

    private void readSseLoop() throws IOException {
        InputStream stream = sseStream;
        if (stream == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String event = null;
            String id = null;
            StringBuilder data = new StringBuilder();
            while (!disconnectRequested && !cancelRequested) {
                String line;
                try {
                    line = reader.readLine();
                } catch (SocketTimeoutException e) {
                    if (disconnectRequested || cancelRequested) {
                        break;
                    }
                    continue;
                } catch (IOException e) {
                    if (disconnectRequested || cancelRequested) {
                        break;
                    }
                    throw e;
                }
                if (line == null) {
                    break;
                }
                if (line.isEmpty()) {
                    dispatchEvent(event, id, data.toString());
                    event = null;
                    id = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    String comment = line.substring(1).trim();
                    if ("ping".equals(comment)) {
                        sendHeartbeat();
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("id:")) {
                    id = line.substring("id:".length()).trim();
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append("\n");
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
        }
    }

    private void dispatchEvent(String event, String id, String data) {
        if (event == null || event.isBlank() || data.isBlank()) {
            return;
        }
        int eventId = parseEventId(id);
        if (eventId < 1 || eventId <= lastEventId) {
            return;
        }
        Map<String, String> kv = parseQueryString(data);
        int senderId = Integer.parseInt(kv.get("senderId"));

        switch (event) {
            case StatusCodes.MESSAGE -> {
                if (senderId == ownId) {
                    return;
                }
                String message = kv.get("message");
                String decrypted = symmetric.decryptChatMessage(urlDecode(message));
                markEventProcessed(eventId);
                callbackExecutor.execute(() -> listener.onTextMessage(senderId, decrypted));
            }
            case StatusCodes.USER_JOIN -> {
                if (senderId == ownId) {
                    return;
                }
                String decrypted = decryptUserName(urlDecode(kv.get("message")), senderId, UNKNOWN_USER_NAME);
                markEventProcessed(eventId);
                callbackExecutor.execute(() -> listener.onUserJoined(senderId, decrypted));
            }
            case StatusCodes.USER_LEAVE -> {
                markEventProcessed(eventId);
                callbackExecutor.execute(() -> listener.onUserLeft(senderId));
            }
            case StatusCodes.USER_TYPING -> {
                if (senderId == ownId) {
                    return;
                }
                long ttlMs;
                try {
                    String message = kv.get("message");
                    String decrypted = symmetric.decryptMessage(urlDecode(message));
                    ttlMs = Long.parseLong(decrypted);
                } catch (Exception e) {
                    Logger.warn(ClientLogMessages.couldNotProcessTypingStatus(senderId));
                    markEventProcessed(eventId);
                    return;
                }
                long expiresAt = ttlMs <= 0 ? 0 : System.currentTimeMillis() + ttlMs;
                markEventProcessed(eventId);
                callbackExecutor.execute(() -> listener.onTyping(senderId, expiresAt));
            }
            case StatusCodes.FILE_META -> {
                if (senderId == ownId) {
                    return;
                }
                String fileId = urlDecode(kv.get("filePublicId"));
                String fileName = urlDecode(kv.get("fileName"));
                long fileSize = Long.parseLong(kv.get("fileSize"));
                String downloadPath = urlDecode(kv.get("downloadPath"));
                String fullDownloadUrl = url(downloadPath);
                markEventProcessed(eventId);
                callbackExecutor.execute(() -> listener.onFilePacket(
                        new FilePacket(StatusCodes.FILE_META, fileId, fileName, fileSize, -1, 0, fullDownloadUrl, senderId, "")
                ));
            }
            default -> {
            }
        }
    }

    private void openSse() throws IOException {
        String sseUrl = url("/api/sessions/" + enc(sessionName) + "/events");
        HttpURLConnection con = (HttpURLConnection) new URL(sseUrl).openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("Accept", "text/event-stream");
        con.setRequestProperty("Cache-Control", "no-cache");
        authorize(con);
        if (lastEventId > 0) {
            con.setRequestProperty(LAST_EVENT_ID_HEADER, String.valueOf(lastEventId));
        }
        con.setDoInput(true);
        con.setConnectTimeout(SSE_CONNECT_TIMEOUT_MS);
        con.setReadTimeout(SSE_CONNECT_TIMEOUT_MS);
        con.connect();

        int code = con.getResponseCode();
        if (code != 200) {
            String body = readBody(con);
            con.disconnect();
            throw new IOException(extractError(body, code));
        }
        this.sseConnection = con;
        this.sseStream = con.getInputStream();
        con.setReadTimeout(SSE_READ_TIMEOUT_MS);
    }

    private void closeSse() {
        InputStream stream = sseStream;
        sseStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        HttpURLConnection con = sseConnection;
        sseConnection = null;
        if (con != null) {
            con.disconnect();
        }
    }

    private HttpResult postJsonResult(String url, String json, boolean authorized, boolean includeAckEventId) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (authorized) {
            authorize(con);
            if (includeAckEventId && lastEventId > 0) {
                con.setRequestProperty(ACK_EVENT_ID_HEADER, String.valueOf(lastEventId));
            }
        }
        con.setDoOutput(true);
        con.setConnectTimeout(API_CONNECT_TIMEOUT_MS);
        con.setReadTimeout(API_READ_TIMEOUT_MS);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        con.setFixedLengthStreamingMode(bytes.length);
        con.connect();
        try (OutputStream out = con.getOutputStream()) {
            out.write(bytes);
        }
        try {
            int code = con.getResponseCode();
            return new HttpResult(code, readBody(con));
        } finally {
            con.disconnect();
        }
    }

    private String postJson(String url, String json) throws IOException {
        return postJson(url, json, false, false);
    }

    private String postJson(String url, String json, boolean authorized, boolean includeAckEventId) throws IOException {
        HttpResult res = postJsonResult(url, json, authorized, includeAckEventId);
        if (res.status != 200) {
            throw new IOException(extractError(res.body, res.status));
        }
        return res.body;
    }

    private void fireAndForgetAuthorizedPostJson(String url, String json, boolean includeAckEventId) {
        Thread.startVirtualThread(() -> {
            try {
                postJson(url, json, true, includeAckEventId);
            } catch (Exception e) {
                Logger.error(ClientLogMessages.withCause(ClientLogMessages.HTTP_REQUEST_FAILED_PREFIX, e), e);
            }
        });
    }

    private void sendDisconnect() {
        if (token == null || token.isBlank()) {
            return;
        }
        fireAndForgetAuthorizedPostJson(url("/api/sessions/" + enc(sessionName) + "/disconnect"), "{}", true);
    }

    private void sendHeartbeat() {
        if (disconnectRequested || cancelRequested) {
            return;
        }
        if (token == null || token.isBlank()) {
            return;
        }
        fireAndForgetAuthorizedPostJson(url("/api/sessions/" + enc(sessionName) + "/heartbeat"), "{}", true);
    }

    private void fail(String reason) {
        callbackExecutor.execute(() -> listener.onConnectionFailed(reason == null ? "" : reason));
    }

    private static Map<String, String> parseKvLines(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) {
            return map;
        }
        for (String line : body.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            int idx = line.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            map.put(line.substring(0, idx), line.substring(idx + 1));
        }
        return map;
    }

    private static String normalizeBaseUrl(String raw) {
        String url = raw.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url + "/public/index.php";
    }

    private String url(String pathOrUrl) {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl;
        }
        if (pathOrUrl.isBlank() || "/".equals(pathOrUrl)) {
            return baseUrl;
        }
        if (pathOrUrl.startsWith("/")) {
            return baseUrl + pathOrUrl;
        }
        return baseUrl + "/" + pathOrUrl;
    }

    private static String readBody(HttpURLConnection con) throws IOException {
        try (InputStream in = con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream()) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String extractError(String body, int status) {
        if (body == null || body.isBlank()) {
            return "HTTP " + status;
        }
        String trimmed = body.trim();
        if ("\"\"".equals(trimmed) || "null".equals(trimmed)) {
            return "HTTP " + status;
        }
        int key = body.indexOf("\"error\"");
        if (key >= 0) {
            int colon = body.indexOf(':', key);
            int q1 = colon < 0 ? -1 : body.indexOf('"', colon + 1);
            int q2 = q1 < 0 ? -1 : body.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) {
                return body.substring(q1 + 1, q2);
            }
        }
        return body.length() > 512 ? body.substring(0, 512) : body;
    }

    private static Map<String, String> parseQueryString(String data) {
        Map<String, String> map = new HashMap<>();
        if (data == null || data.isBlank()) {
            return map;
        }
        String[] parts = data.split("&");
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            map.put(part.substring(0, idx), part.substring(idx + 1));
        }
        return map;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String hashSessionName(String sessionName, String roomSecret) {
        String normalized = sessionName.trim();
        if (roomSecret == null || roomSecret.isBlank()) {
            return normalized;
        }
        byte[] input = normalized.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] hash = CryptoUtils.hmacSha256(roomSecret, input);
            return toHex(hash);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to hash session name", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xff;
            out.append(Character.forDigit(v >>> 4, 16));
            out.append(Character.forDigit(v & 0x0f, 16));
        }
        return out.toString();
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String s) {
        return s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
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

    private void markEventProcessed(int eventId) {
        if (eventId > lastEventId) {
            lastEventId = eventId;
        }
    }

    private int parseEventId(String id) {
        if (id == null || id.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String tokenHeaderValue() {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing session token");
        }
        return token;
    }

    private boolean isChatEncrypted() {
        return config.roomSecret() != null && !config.roomSecret().isBlank();
    }

    private record HttpResult(int status, String body) {
    }
}
