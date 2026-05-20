package thatsapp.client.communication.filetransfer.web;

import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.filetransfer.api.FileTransferUi;
import thatsapp.client.communication.filetransfer.api.FileTransferView;
import thatsapp.client.communication.filetransfer.common.AbstractFileTransferService;
import thatsapp.client.communication.transport.web.WebserverTransport;
import thatsapp.client.messages.ClientAttentionMessages;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public final class HttpFileTransferService extends AbstractFileTransferService {

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int TUS_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final String TUS_VERSION = "1.0.0";
    private static final int HTTP_CONNECT_TIMEOUT_MS = 10000;
    private static final int HTTP_REQUEST_TIMEOUT_MS = 30000;
    private static final int DOWNLOAD_CONNECT_TIMEOUT_MS = 10000;
    private static final int DOWNLOAD_READ_TIMEOUT_MS = 30000;
    private static final String UNKNOWN_FILE_NAME = "Unknown file";

    private final Supplier<WebserverTransport> transportSupplier;
    private final IntFunction<String> userNameLookup;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(HTTP_CONNECT_TIMEOUT_MS))
            .build();

    private final Deque<UploadTask> uploadQueue = new ArrayDeque<>();
    private final Map<String, DownloadTask> downloads = new HashMap<>();

    private UploadTask activeUpload;

    public HttpFileTransferService(Supplier<WebserverTransport> transportSupplier, FileTransferUi ui, IntFunction<String> userNameLookup) {
        super(ui, 0L);
        this.transportSupplier = transportSupplier;
        this.userNameLookup = userNameLookup;
    }

    @Override
    public synchronized void enqueueUploads(List<File> files) {
        List<File> validFiles = filterUploadFiles(files);
        if (validFiles.isEmpty()) {
            return;
        }
        for (File file : validFiles) {
            UploadTask task = new UploadTask(file);
            if (activeUpload != null) {
                task.showQueuedWaiting(() -> {
                    synchronized (HttpFileTransferService.this) {
                        uploadQueue.remove(task);
                    }
                });
            }
            uploadQueue.add(task);
        }
        startNextUpload();
    }

    @Override
    public void handleIncoming(FilePacket packet) {
        if (packet == null || !StatusCodes.FILE_META.equals(packet.status())) {
            return;
        }
        if (!fileTransferEnabled) {
            return;
        }
        String displayName = displayName(packet.fileName(), packet.fileId());
        if (exceedsFileLimit(packet.fileSize())) {
            Logger.warn(ClientLogMessages.rejectedFileOffer(displayName, packet.fileSize(), fileSizeBytesLimit));
            ui.showError(ClientAttentionMessages.fileExceedsLimit(toMb(fileSizeBytesLimit)));
            return;
        }
        String senderName = userNameLookup.apply(packet.senderId());
        if (ui.autoDownload()) {
            startDownload(packet, senderName, displayName, null);
            return;
        }
        FileTransferView view = ui.showIncoming(displayName, senderName);
        view.showDownloadPrompt(() -> startDownload(packet, senderName, displayName, view));
        view.updateProgress(0, STATUS_START_DOWNLOAD);
    }

    @Override
    public synchronized void cancelAll() {
        uploadQueue.forEach(t -> t.cancel(REASON_DISCONNECTED));
        uploadQueue.clear();
        if (activeUpload != null) {
            activeUpload.cancel(REASON_DISCONNECTED);
            activeUpload = null;
        }
        downloads.values().forEach(t -> t.cancel(REASON_DISCONNECTED));
        downloads.clear();
    }

    private synchronized void startNextUpload() {
        if (activeUpload != null) {
            return;
        }
        UploadTask next = uploadQueue.poll();
        if (next == null) {
            return;
        }
        activeUpload = next;
        next.start(() -> {
            synchronized (HttpFileTransferService.this) {
                activeUpload = null;
                startNextUpload();
            }
        });
    }

    private void startDownload(FilePacket packet, String senderName, String displayName, FileTransferView existingView) {
        if (packet == null) {
            return;
        }
        synchronized (this) {
            if (downloads.containsKey(packet.fileId())) {
                return;
            }
        }
        FileTransferView view = existingView != null ? existingView : ui.showIncoming(displayName, senderName);
        DownloadTask task = new DownloadTask(displayName, packet.fileSize(), packet.payload(), view);
        synchronized (this) {
            downloads.put(packet.fileId(), task);
        }
        task.start(() -> {
            synchronized (HttpFileTransferService.this) {
                downloads.remove(packet.fileId());
            }
        });
    }

    private HttpResponse<Void> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted");
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encryptFileName(String fileName) {
        return currentSymmetric().encryptFileName(fileName);
    }

    private String decryptFileName(String fileName) {
        return currentSymmetric().decryptFileName(fileName);
    }

    private String displayName(String fileName, String fileId) {
        try {
            return decryptFileName(fileName);
        } catch (Exception e) {
            Logger.warn(ClientLogMessages.couldNotDecryptFileName(fileId));
            return UNKNOWN_FILE_NAME;
        }
    }

    private WebserverTransport currentTransport() {
        WebserverTransport transport = transportSupplier.get();
        if (transport == null) {
            throw new IllegalStateException("Missing web transport");
        }
        return transport;
    }

    private SymmetricEncryption currentSymmetric() {
        WebserverTransport transport = currentTransport();
        if (transport.getSymmetric() == null) {
            throw new IllegalStateException("Missing encryption context");
        }
        return transport.getSymmetric();
    }

    private boolean isContentEncryptionEnabled() {
        return currentSymmetric().isEnabled();
    }

    private void decryptFile(Path source, Path target) throws IOException {
        currentSymmetric().decryptFile(source, target);
    }

    private HttpRequest.Builder authorizedRequestBuilder(URI uri) {
        WebserverTransport transport = currentTransport();
        return transport.authorize(HttpRequest.newBuilder(uri))
                .timeout(Duration.ofMillis(HTTP_REQUEST_TIMEOUT_MS));
    }

    private void authorize(HttpURLConnection connection) {
        currentTransport().authorize(connection);
    }

    private final class UploadTask {
        private final File file;
        private final AtomicBoolean canceled = new AtomicBoolean(false);
        private FileTransferView view;
        private Path uploadSourcePath;
        private long uploadSize;
        private boolean tempSource;

        UploadTask(File file) {
            this.file = file;
        }

        void showQueuedWaiting(Runnable removeFromQueue) {
            ensureView();
            view.updateProgress(0, STATUS_WAITING_FOR_PREVIOUS_UPLOAD);
            view.setCancelAction(() -> {
                removeFromQueue.run();
                cancel(REASON_REMOVED_FROM_QUEUE);
            });
        }

        void start(Runnable onFinished) {
            ensureView();
            view.setCancelAction(() -> cancel(REASON_UPLOAD_CANCELED));
            Thread.startVirtualThread(() -> {
                try {
                    upload();
                    if (!canceled.get()) {
                        view.markFinished();
                    }
                } catch (Exception e) {
                    if (!canceled.get()) {
                        view.markCanceled("Error: " + e.getMessage());
                    }
                    Logger.error(ClientLogMessages.withCause(ClientLogMessages.UPLOAD_FAILED_PREFIX, e), e);
                } finally {
                    onFinished.run();
                }
            });
        }

        void cancel(String reason) {
            if (!canceled.compareAndSet(false, true)) {
                return;
            }
            view.markCanceled(reason);
        }

        private void upload() throws IOException {
            WebserverTransport transport = transportSupplier.get();
            prepareUploadSource(transport);
            try {
                String uploadUrl = createUpload(transport);
                sendChunks(uploadUrl);
            } finally {
                cleanupUploadSource();
            }
        }

        private String createUpload(WebserverTransport transport) throws IOException {
            String url = transport.baseUrl()
                    + "/api/sessions/" + enc(transport.sessionName())
                    + "/uploads";
            String payloadName = encryptFileName(file.getName());
            String metadata = "filename " + Base64.getEncoder().encodeToString(payloadName.getBytes(StandardCharsets.UTF_8));
            HttpRequest request = transport.authorize(HttpRequest.newBuilder(URI.create(url)))
                    .timeout(Duration.ofMillis(HTTP_REQUEST_TIMEOUT_MS))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Tus-Resumable", TUS_VERSION)
                    .header("Upload-Length", String.valueOf(uploadSize))
                    .header("Upload-Metadata", metadata)
                    .build();
            HttpResponse<Void> response = send(request);
            if (response.statusCode() != 201) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.headers().firstValue("Location").orElseThrow();
        }

        private void sendChunks(String uploadUrl) throws IOException {
            long size = uploadSize;
            long offset = 0L;
            try (InputStream in = Files.newInputStream(uploadSourcePath)) {
                while (offset < size) {
                    if (canceled.get()) {
                        return;
                    }
                    int toRead = (int) Math.min(TUS_CHUNK_SIZE, size - offset);
                    byte[] chunk = in.readNBytes(toRead);
                    if (chunk.length == 0) {
                        return;
                    }
                    HttpRequest request = authorizedRequestBuilder(URI.create(uploadUrl))
                            .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(chunk))
                            .header("Tus-Resumable", TUS_VERSION)
                            .header("Upload-Offset", String.valueOf(offset))
                            .header("Content-Type", "application/offset+octet-stream")
                            .build();
                    HttpResponse<Void> response = send(request);
                    if (response.statusCode() != 204) {
                        throw new IOException("HTTP " + response.statusCode());
                    }
                    offset = response.headers()
                            .firstValue("Upload-Offset")
                            .map(Long::parseLong)
                            .orElseThrow(() -> new IOException("Missing Upload-Offset"));
                    double progress = Math.min(1.0, (double) offset / size);
                    view.updateProgress(progress, formatProgress(offset, size));
                }
            }
        }

        private void prepareUploadSource(WebserverTransport transport) throws IOException {
            if (uploadSourcePath != null) {
                return;
            }
            SymmetricEncryption symmetric = transport.getSymmetric();
            if (symmetric.isEnabled()) {
                Path temp = Files.createTempFile("thatsapp-upload-", ".enc");
                try {
                    symmetric.encryptFile(file.toPath(), temp);
                    uploadSourcePath = temp;
                    uploadSize = Files.size(temp);
                    tempSource = true;
                } catch (IOException e) {
                    Files.deleteIfExists(temp);
                    throw e;
                }
                return;
            }
            uploadSourcePath = file.toPath();
            uploadSize = file.length();
        }

        private void cleanupUploadSource() {
            if (tempSource && uploadSourcePath != null) {
                try {
                    Files.deleteIfExists(uploadSourcePath);
                } catch (IOException ignored) {
                }
            }
            tempSource = false;
            uploadSourcePath = null;
        }

        private void ensureView() {
            if (view == null) {
                view = ui.showOutgoing(file.getName());
            }
        }
    }

    private final class DownloadTask {
        private final String fileName;
        private final long fileSize;
        private final String downloadUrl;
        private final FileTransferView view;
        private final AtomicBoolean canceled = new AtomicBoolean(false);
        private volatile HttpURLConnection con;
        private volatile Path targetPath;
        private volatile Path tempPath;

        DownloadTask(String fileName, long fileSize, String downloadUrl, FileTransferView view) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.downloadUrl = downloadUrl;
            this.view = view;
        }

        void start(Runnable onFinished) {
            view.setCancelAction(() -> cancel(REASON_DOWNLOAD_CANCELED));
            Thread.startVirtualThread(() -> {
                try {
                    download();
                    if (!canceled.get()) {
                        view.markFinished();
                    }
                } catch (Exception e) {
                    if (!canceled.get()) {
                        cancel("Error: " + e.getMessage());
                    }
                    Logger.error(ClientLogMessages.withCause(ClientLogMessages.DOWNLOAD_FAILED_PREFIX, e), e);
                } finally {
                    onFinished.run();
                }
            });
        }

        void cancel(String reason) {
            if (!canceled.compareAndSet(false, true)) {
                return;
            }
            view.markCanceled(reason);
            HttpURLConnection c = con;
            if (c != null) {
                c.disconnect();
            }
            Path tmp = tempPath;
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
            Path t = targetPath;
            if (t != null) {
                try {
                    Files.deleteIfExists(t);
                } catch (IOException ignored) {
                }
            }
        }

        private void download() throws IOException {
            Path target = buildTargetPath(fileName);
            this.targetPath = target;
            Files.createDirectories(target.getParent());
            boolean decrypt = isContentEncryptionEnabled();
            Path downloadTarget = target;
            if (decrypt) {
                Path temp = Files.createTempFile("thatsapp-download-", ".enc");
                this.tempPath = temp;
                downloadTarget = temp;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(downloadUrl).openConnection();
            this.con = c;
            try {
                c.setRequestMethod("GET");
                c.setDoInput(true);
                c.setConnectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS);
                c.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MS);
                authorize(c);
                c.connect();

                int code = c.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }

                long received = 0L;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(downloadTarget.toFile())) {
                    view.updateProgress(0, formatProgress(0, fileSize));
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (canceled.get()) {
                            return;
                        }
                        out.write(buffer, 0, read);
                        received += read;
                        double progress = fileSize == 0 ? 1.0 : Math.min(1.0, (double) received / fileSize);
                        view.updateProgress(progress, formatProgress(received, fileSize));
                    }
                }
                if (decrypt && !canceled.get()) {
                    decryptFile(downloadTarget, target);
                    Files.deleteIfExists(downloadTarget);
                }
            } finally {
                this.con = null;
                c.disconnect();
            }
        }
    }
}
