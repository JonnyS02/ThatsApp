package thatsapp.client.communication.transport.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TestWebserver implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final CompletableFuture<Void> acceptLoop;
    private final String handshakeBody;
    private final String connectBody;
    private final String sseBody;
    private final int sseCloseDelayMs;
    private final String uploadToken;

    public final CountDownLatch messageReceived = new CountDownLatch(1);
    public final CountDownLatch typingReceived = new CountDownLatch(1);
    public final CountDownLatch heartbeatReceived = new CountDownLatch(1);
    public final CountDownLatch disconnectReceived = new CountDownLatch(1);
    public volatile int handshakeStatus = 200;
    public volatile String messageToken = "";
    public volatile String messageBody = "";
    public volatile String typingToken = "";
    public volatile String typingBody = "";
    public volatile String heartbeatToken = "";
    public volatile String heartbeatAckEventId = "";
    public volatile String disconnectToken = "";
    public volatile String disconnectAckEventId = "";
    public volatile int createRequests;
    public volatile int chunkRequests;
    public volatile long receivedChunkBytes;
    public volatile String chunkMethod = "";

    public static TestWebserver transport(
            String handshakeBody,
            List<String> connectLines,
            String sseBody,
            int sseCloseDelayMs
    ) throws IOException {
        return new TestWebserver(handshakeBody, connectLines, sseBody, sseCloseDelayMs, null);
    }

    public static TestWebserver upload(String uploadToken) throws IOException {
        return new TestWebserver("", List.of(), "", 0, uploadToken);
    }

    private TestWebserver(
            String handshakeBody,
            List<String> connectLines,
            String sseBody,
            int sseCloseDelayMs,
            String uploadToken
    ) throws IOException {
        this.serverSocket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        this.handshakeBody = handshakeBody;
        this.connectBody = String.join("\n", connectLines);
        this.sseBody = sseBody;
        this.sseCloseDelayMs = sseCloseDelayMs;
        this.uploadToken = uploadToken;
        this.acceptLoop = CompletableFuture.runAsync(this::runAcceptLoop);
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + serverSocket.getLocalPort();
    }

    @Override
    public void close() throws Exception {
        serverSocket.close();
        try {
            acceptLoop.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Closing the server socket terminates the loop.
        }
    }

    private void runAcceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Thread.startVirtualThread(() -> handle(socket));
            } catch (SocketException e) {
                if (!serverSocket.isClosed()) {
                    throw new IllegalStateException(e);
                }
                return;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = socket.getOutputStream()) {
            HttpRequestData request = readRequest(reader);
            if (request == null) {
                return;
            }
            if (uploadToken != null) {
                handleUploadRequest(request, out);
                return;
            }
            handleTransportRequest(request, out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void handleUploadRequest(HttpRequestData request, OutputStream out) throws IOException {
        if (request.path().endsWith("/uploads")) {
            if (!"POST".equals(request.method()) || !uploadToken.equals(request.header("X-ThatsApp-Token"))) {
                writeResponse(out, 403, "text/plain; charset=utf-8", Map.of(), "");
                return;
            }
            createRequests++;
            writeResponse(
                    out,
                    201,
                    "text/plain; charset=utf-8",
                    Map.of("Location", baseUrl() + "/api/sessions/room/uploads/upload-1"),
                    ""
            );
            return;
        }
        if (request.path().endsWith("/uploads/upload-1")) {
            if (!"PATCH".equals(request.method()) || !uploadToken.equals(request.header("X-ThatsApp-Token"))) {
                writeResponse(out, 403, "text/plain; charset=utf-8", Map.of(), "");
                return;
            }
            chunkRequests++;
            chunkMethod = request.method();
            receivedChunkBytes = request.bodyLength();
            writeResponse(
                    out,
                    204,
                    "text/plain; charset=utf-8",
                    Map.of("Upload-Offset", String.valueOf(request.bodyLength())),
                    ""
            );
            return;
        }
        writeResponse(out, 404, "text/plain; charset=utf-8", Map.of(), "missing");
    }

    private void handleTransportRequest(HttpRequestData request, OutputStream out) throws IOException {
        if (request.path().endsWith("/handshake")) {
            writeResponse(out, handshakeStatus, "text/plain; charset=utf-8", Map.of(), handshakeBody);
            return;
        }
        if (request.path().endsWith("/connect")) {
            writeResponse(out, 200, "text/plain; charset=utf-8", Map.of(), connectBody);
            return;
        }
        if (request.path().endsWith("/events")) {
            writeSseResponse(out, sseBody, sseCloseDelayMs);
            return;
        }
        if (request.path().endsWith("/messages")) {
            messageToken = request.header("X-ThatsApp-Token");
            messageBody = request.body();
            messageReceived.countDown();
            writeResponse(out, 200, "text/plain; charset=utf-8", Map.of(), "");
            return;
        }
        if (request.path().endsWith("/typing")) {
            typingToken = request.header("X-ThatsApp-Token");
            typingBody = request.body();
            typingReceived.countDown();
            writeResponse(out, 200, "text/plain; charset=utf-8", Map.of(), "");
            return;
        }
        if (request.path().endsWith("/heartbeat")) {
            heartbeatToken = request.header("X-ThatsApp-Token");
            heartbeatAckEventId = request.header("X-Last-Event-ID");
            heartbeatReceived.countDown();
            writeResponse(out, 200, "text/plain; charset=utf-8", Map.of(), "");
            return;
        }
        if (request.path().endsWith("/disconnect")) {
            disconnectToken = request.header("X-ThatsApp-Token");
            disconnectAckEventId = request.header("X-Last-Event-ID");
            disconnectReceived.countDown();
            writeResponse(out, 200, "text/plain; charset=utf-8", Map.of(), "");
            return;
        }
        writeResponse(out, 404, "text/plain; charset=utf-8", Map.of(), "missing");
    }

    private static HttpRequestData readRequest(BufferedReader reader) throws IOException {
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isBlank()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        Map<String, String> headers = new HashMap<>();
        int contentLength = 0;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            String name = line.substring(0, separator);
            String value = line.substring(separator + 1).trim();
            headers.put(name, value);
            if ("Content-Length".equalsIgnoreCase(name)) {
                contentLength = Integer.parseInt(value);
            }
        }
        char[] bodyChars = new char[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = reader.read(bodyChars, offset, contentLength - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return new HttpRequestData(parts[0], parts[1], headers, new String(bodyChars, 0, offset), offset);
    }

    private static void writeResponse(
            OutputStream out,
            int status,
            String contentType,
            Map<String, String> headers,
            String body
    ) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder response = new StringBuilder()
                .append("HTTP/1.1 ")
                .append(status)
                .append(' ')
                .append(statusText(status))
                .append("\r\n")
                .append("Content-Type: ")
                .append(contentType)
                .append("\r\n")
                .append("Content-Length: ")
                .append(bytes.length)
                .append("\r\n")
                .append("Connection: close\r\n");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            response.append(entry.getKey())
                    .append(": ")
                    .append(entry.getValue())
                    .append("\r\n");
        }
        response.append("\r\n");
        out.write(response.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private static void writeSseResponse(OutputStream out, String body, int delayMs) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.flush();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            default -> "Status";
        };
    }

    private record HttpRequestData(String method, String path, Map<String, String> headers, String body, int bodyLength) {
        private String header(String name) {
            return headers.getOrDefault(name, "");
        }
    }
}
