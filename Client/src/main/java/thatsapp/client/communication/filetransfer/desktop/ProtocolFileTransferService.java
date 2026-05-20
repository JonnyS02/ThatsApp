package thatsapp.client.communication.filetransfer.desktop;

import javafx.application.Platform;
import thatsapp.client.communication.filetransfer.api.FileTransferUi;
import thatsapp.client.communication.filetransfer.api.FileTransferView;
import thatsapp.client.communication.filetransfer.common.AbstractFileTransferService;
import thatsapp.client.communication.transport.CommunicationClient;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import thatsapp.client.messages.ClientAttentionMessages;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;

/**
 * Handles file uploads and downloads, including UI updates and protocol messages.
 */
public class ProtocolFileTransferService extends AbstractFileTransferService {

    private static final int MAX_PARALLEL_DOWNLOADS = 10;
    private static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024L; // 2 GB
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final String UNKNOWN_FILE_NAME = "Unknown file";

    private final Supplier<CommunicationClient> clientSupplier;
    private final IntFunction<String> userNameLookup;
    private final Deque<UploadTask> uploadQueue = new ArrayDeque<>();
    private final Deque<QueuedDownload> downloadQueue = new ArrayDeque<>();
    private final Map<String, UploadTask> activeUploads = new HashMap<>();
    private final Map<String, DownloadTask> activeDownloads = new HashMap<>();
    private final Map<String, PendingDownload> pendingDownloads = new HashMap<>();
    private final Object stateLock = new Object();

    private boolean uploadRunning = false;

    public ProtocolFileTransferService(Supplier<CommunicationClient> clientSupplier, FileTransferUi ui, IntFunction<String> userNameLookup) {
        super(ui, MAX_TOTAL_BYTES);
        this.clientSupplier = clientSupplier;
        this.userNameLookup = userNameLookup;
    }

    @Override
    public void enqueueUploads(List<File> files) {
        List<File> validFiles = filterUploadFiles(files);
        if (validFiles.isEmpty()) {
            return;
        }
        long totalBytes = 0L;
        for (File file : validFiles) {
            totalBytes += file.length();
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            ui.showError(ClientAttentionMessages.TOTAL_SELECTION_SIZE_LIMIT);
            return;
        }
        List<UploadTask> tasks = createUploadTasks(validFiles);
        synchronized (stateLock) {
            for (UploadTask task : tasks) {
                if (uploadRunning) {
                    task.showQueuedWaiting();
                }
                uploadQueue.add(task);
            }
        }
        Logger.info(ClientLogMessages.queuedFilesForUpload(validFiles.size()));
        startNextUpload();
    }

    @Override
    public void handleIncoming(FilePacket packet) {
        if (packet == null) {
            return;
        }
        switch (packet.status()) {
            case StatusCodes.FILE_META -> handleMeta(packet);
            case StatusCodes.FILE_CHUNK -> handleChunk(packet);
            case StatusCodes.FILE_COMPLETE -> handleComplete(packet);
            case StatusCodes.FILE_CANCEL -> handleCancel(packet);
            default -> {
            }
        }
    }

    @Override
    public void cancelAll() {
        List<UploadTask> uploads;
        List<DownloadTask> downloads;
        List<QueuedDownload> queued;
        List<PendingDownload> pending;
        synchronized (stateLock) {
            uploads = new ArrayList<>(activeUploads.values());
            downloads = new ArrayList<>(activeDownloads.values());
            queued = new ArrayList<>(downloadQueue);
            pending = new ArrayList<>(pendingDownloads.values());
            uploadQueue.clear();
            activeUploads.clear();
            activeDownloads.clear();
            downloadQueue.clear();
            pendingDownloads.clear();
            uploadRunning = false;
        }
        uploads.forEach(task -> task.markCanceled(REASON_DISCONNECTED));
        downloads.forEach(task -> task.markCanceled(REASON_DISCONNECTED, false, false));
        queued.forEach(q -> q.view().markCanceled(REASON_DISCONNECTED));
        pending.forEach(p -> p.view().markCanceled(REASON_DISCONNECTED));
        Logger.warn(ClientLogMessages.CLEARED_ALL_TRANSFERS_DISCONNECT);
    }

    private List<UploadTask> createUploadTasks(List<File> validFiles) {
        List<UploadTask> tasks = new ArrayList<>();
        for (File file : validFiles) {
            UploadTask task = new UploadTask(
                    file,
                    CHUNK_SIZE,
                    clientSupplier,
                    ui,
                    queuedTask -> {
                        synchronized (stateLock) {
                            uploadQueue.remove(queuedTask);
                        }
                    },
                    fileId -> {
                        synchronized (stateLock) {
                            activeUploads.remove(fileId);
                            uploadRunning = false;
                        }
                        startNextUpload();
                    }
            );
            tasks.add(task);
        }
        return tasks;
    }

    private void startNextUpload() {
        UploadTask next;
        synchronized (stateLock) {
            if (uploadRunning) {
                return;
            }
            next = uploadQueue.poll();
            if (next == null) {
                return;
            }
            uploadRunning = true;
            activeUploads.put(next.fileId(), next);
        }
        next.start();
    }

    private void handleMeta(FilePacket packet) {
        if (!fileTransferEnabled) {
            try {
                sendPacket(new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", 0, REASON_FILE_TRANSFER_DISABLED));
            } catch (IOException ignored) {
            }
            return;
        }
        synchronized (stateLock) {
            if (activeDownloads.containsKey(packet.fileId())
                    || pendingDownloads.containsKey(packet.fileId())
                    || isQueuedLocked(packet.fileId())) {
                return;
            }
        }
        if (exceedsFileLimit(packet.fileSize())) {
            String displayName = displayName(packet.fileName(), packet.fileId());
            Logger.warn(ClientLogMessages.declinedDownloadOffer(displayName, packet.fileSize(), fileSizeBytesLimit));
            try {
                sendPacket(new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", 0, REASON_FILE_EXCEEDS_LIMIT));
            } catch (IOException ignored) {
            }
            return;
        }
        String senderName = userNameLookup.apply(packet.senderId());
        try {
            String displayName = displayName(packet.fileName(), packet.fileId());
            Logger.info(ClientLogMessages.incomingFileOffer(senderName, displayName, packet.fileSize()));
            if (!ui.autoDownload()) {
                FileTransferView view = ui.showIncoming(displayName, senderName);
                PendingDownload pending = new PendingDownload(packet, senderName, view);
                synchronized (stateLock) {
                    pendingDownloads.put(packet.fileId(), pending);
                }
                view.showDownloadPrompt(() -> startDownload(packet, senderName, view));
                view.updateProgress(0, STATUS_START_DOWNLOAD);
                Logger.info(ClientLogMessages.awaitingUserConfirmationToDownload(displayName, senderName));
                return;
            }
            startDownload(packet, senderName, null);
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.COULD_NOT_START_DOWNLOAD_PREFIX, e), e);
            try {
                sendPacket(new FilePacket(StatusCodes.FILE_CANCEL, packet.fileId(), packet.fileName(), packet.fileSize(), 0, packet.totalChunks(), "", 0, REASON_DOWNLOAD_COULD_NOT_BE_STARTED));
            } catch (IOException ignored) {
            }
        }
    }

    private void startDownload(FilePacket packet, String senderName, FileTransferView existingView) {
        if (packet == null) {
            return;
        }
        FileTransferView view = existingView;
        PendingDownload pending;
        synchronized (stateLock) {
            if (activeDownloads.containsKey(packet.fileId()) || isQueuedLocked(packet.fileId())) {
                return;
            }
            pending = pendingDownloads.remove(packet.fileId());
        }
        if (view == null && pending != null) {
            view = pending.view();
        }
        boolean queueDownload;
        synchronized (stateLock) {
            queueDownload = activeDownloads.size() >= MAX_PARALLEL_DOWNLOADS;
        }
        if (queueDownload) {
            String displayName = displayName(packet.fileName(), packet.fileId());
            FileTransferView waitingView = view != null ? view : ui.showIncoming(displayName, senderName);
            waitingView.updateProgress(0, STATUS_WAITING_FOR_PREVIOUS_DOWNLOADS);
            waitingView.setCancelAction(() -> {
                synchronized (stateLock) {
                    downloadQueue.removeIf(q -> q.packet().fileId().equals(packet.fileId()));
                }
                waitingView.markCanceled(REASON_REMOVED_FROM_QUEUE);
            });
            synchronized (stateLock) {
                downloadQueue.add(new QueuedDownload(packet, senderName, waitingView));
            }
            Logger.info(ClientLogMessages.queuedDownload(displayName));
            return;
        }
        startDownloadNow(packet, senderName, view);
    }

    private void startDownloadNow(FilePacket packet, String senderName, FileTransferView view) {
        BiConsumer<String, Boolean> onCanceled = (id, advanceQueue) -> {
            synchronized (stateLock) {
                activeDownloads.remove(id);
            }
            if (advanceQueue) {
                startNextQueuedDownload();
            }
        };
        Function<String, Path> targetPathFactory = this::buildTargetPath;
        String displayName = displayName(packet.fileName(), packet.fileId());
        DownloadTask task = new DownloadTask(packet.fileId(), packet.fileName(), displayName, packet.fileSize(), packet.totalChunks(), senderName, view, clientSupplier, ui, targetPathFactory, onCanceled);
        synchronized (stateLock) {
            activeDownloads.put(packet.fileId(), task);
        }
        try {
            requestDownload(packet);
        } catch (IOException e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.COULD_NOT_REQUEST_DOWNLOAD_PREFIX, e), e);
            task.markCanceled(REASON_DOWNLOAD_REQUEST_FAILED, false);
            synchronized (stateLock) {
                activeDownloads.remove(packet.fileId());
            }
            startNextQueuedDownload();
        }
    }

    private void startNextQueuedDownload() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::startNextQueuedDownload);
            return;
        }
        while (true) {
            QueuedDownload next;
            synchronized (stateLock) {
                if (activeDownloads.size() >= MAX_PARALLEL_DOWNLOADS) {
                    return;
                }
                next = downloadQueue.poll();
                if (next == null) {
                    return;
                }
            }
            startDownloadNow(next.packet(), next.senderName(), next.view());
        }
    }

    private boolean isQueuedLocked(String fileId) {
        for (QueuedDownload queued : downloadQueue) {
            if (queued.packet().fileId().equals(fileId)) {
                return true;
            }
        }
        return false;
    }

    private QueuedDownload removeQueuedLocked(String fileId) {
        Iterator<QueuedDownload> it = downloadQueue.iterator();
        while (it.hasNext()) {
            QueuedDownload q = it.next();
            if (q.packet().fileId().equals(fileId)) {
                it.remove();
                return q;
            }
        }
        return null;
    }

    private void requestDownload(FilePacket packet) throws IOException {
        sendPacket(new FilePacket(StatusCodes.FILE_REQUEST, packet.fileId(), packet.fileName(), packet.fileSize(), -1, packet.totalChunks(), "", 0, ""));
    }

    private void handleChunk(FilePacket packet) {
        DownloadTask task;
        synchronized (stateLock) {
            task = activeDownloads.get(packet.fileId());
        }
        if (task == null || task.isCanceled()) {
            return;
        }
        task.enqueueChunk(packet.payload(), packet.chunkIndex(), packet.totalChunks());
    }

    private void handleComplete(FilePacket packet) {
        DownloadTask task;
        synchronized (stateLock) {
            task = activeDownloads.get(packet.fileId());
        }
        if (task == null) {
            return;
        }
        if (task.isCanceled()) {
            synchronized (stateLock) {
                activeDownloads.remove(packet.fileId());
            }
            startNextQueuedDownload();
            return;
        }
        task.enqueueFinish(() -> {
            synchronized (stateLock) {
                activeDownloads.remove(packet.fileId());
            }
            startNextQueuedDownload();
        });
    }

    private void handleCancel(FilePacket packet) {
        UploadTask upload;
        DownloadTask task;
        PendingDownload pending;
        QueuedDownload queued;
        synchronized (stateLock) {
            upload = activeUploads.remove(packet.fileId());
            if (upload != null) {
                task = null;
                pending = null;
                queued = null;
            } else {
                task = activeDownloads.remove(packet.fileId());
            }
            if (upload != null || task != null) {
                pending = null;
                queued = null;
            } else {
                pending = pendingDownloads.remove(packet.fileId());
                queued = pending == null ? removeQueuedLocked(packet.fileId()) : null;
            }
        }
        String reason = packet.reason() == null ? "" : packet.reason();
        if (upload != null) {
            upload.markCanceled(reason.isBlank() ? REASON_UPLOAD_CANCELED : reason);
            return;
        }
        if (task != null) {
            task.markCanceled(REASON_CANCELED_PREFIX + reason, false);
            startNextQueuedDownload();
            return;
        }
        if (pending != null) {
            pending.view().markCanceled(REASON_CANCELED_PREFIX + reason);
            return;
        }
        if (queued != null) {
            queued.view().markCanceled(REASON_CANCELED_PREFIX + reason);
            return;
        }
        String displayName = displayName(packet.fileName(), packet.fileId());
        Logger.warn(ClientLogMessages.downloadCanceledFor(displayName, reason));
    }

    private void sendPacket(FilePacket packet) throws IOException {
        clientSupplier.get().sendFilePacket(packet);
    }

    private String decryptFileName(String fileName) {
        CommunicationClient client = clientSupplier.get();
        if (client == null || client.getSymmetric() == null) {
            throw new IllegalStateException("Missing encryption context");
        }
        return client.getSymmetric().decryptFileName(fileName);
    }

    private String displayName(String fileName, String fileId) {
        try {
            return decryptFileName(fileName);
        } catch (Exception e) {
            Logger.warn(ClientLogMessages.couldNotDecryptFileName(fileId));
            return UNKNOWN_FILE_NAME;
        }
    }

    private record PendingDownload(FilePacket packet, String senderName, FileTransferView view) {
    }

    private record QueuedDownload(FilePacket packet, String senderName, FileTransferView view) {
    }
}
