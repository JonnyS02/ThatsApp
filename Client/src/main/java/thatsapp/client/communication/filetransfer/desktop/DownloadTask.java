package thatsapp.client.communication.filetransfer.desktop;

import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.filetransfer.api.FileTransferUi;
import thatsapp.client.communication.filetransfer.api.FileTransferView;
import thatsapp.client.communication.filetransfer.common.AbstractFileTransferService;
import thatsapp.client.communication.filetransfer.common.AbstractTransferTask;
import thatsapp.client.communication.transport.CommunicationClient;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class DownloadTask extends AbstractTransferTask {
    private final String fileId;
    private final String fileNameEncrypted;
    private final String fileNameDisplay;
    private final long fileSize;
    private final int totalChunks;
    private final FileOutputStream fos;
    private final Path targetPath;
    private final Supplier<CommunicationClient> clientSupplier;
    private final BiConsumer<String, Boolean> onCanceled;
    private final ExecutorService ioExecutor;

    private long receivedBytes = 0;
    private int expectedChunkIndex = 0;

    DownloadTask(
            String fileId,
            String fileNameEncrypted,
            String fileNameDisplay,
            long fileSize,
            int totalChunks,
            String senderName,
            FileTransferView view,
            Supplier<CommunicationClient> clientSupplier,
            FileTransferUi ui,
            Function<String, Path> targetPathFactory,
            BiConsumer<String, Boolean> onCanceled
    ) {
        super(fileSize);
        this.fileId = fileId;
        this.fileNameEncrypted = fileNameEncrypted;
        this.fileNameDisplay = fileNameDisplay;
        this.fileSize = fileSize;
        this.totalChunks = totalChunks;
        this.clientSupplier = clientSupplier;
        this.onCanceled = onCanceled;
        this.targetPath = targetPathFactory.apply(fileNameDisplay);
        try {
            Files.createDirectories(targetPath.getParent());
            this.fos = new FileOutputStream(targetPath.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create file: " + e.getMessage(), e);
        }
        FileTransferView resolvedView = view == null ? ui.showIncoming(fileNameDisplay, senderName) : view;
        setView(resolvedView);
        this.view.setCancelAction(() -> markCanceled(AbstractFileTransferService.REASON_DOWNLOAD_CANCELED, true));
        updateProgress(0);
        Logger.info(ClientLogMessages.downloadStarted(fileNameDisplay, senderName, fileSize));
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "download-" + fileId);
            thread.setDaemon(true);
            return thread;
        });
    }

    void enqueueChunk(String payload, int chunkIndex, int total) {
        if (isCanceled()) {
            return;
        }
        try {
            ioExecutor.execute(() -> writeChunkInternal(payload, chunkIndex, total));
        } catch (RejectedExecutionException e) {
            if (!isCanceled()) {
                Logger.warn(ClientLogMessages.downloadSkippedChunk(fileNameDisplay));
            }
        }
    }

    void enqueueFinish(Runnable onFinished) {
        try {
            ioExecutor.execute(() -> {
                finishInternal();
                if (onFinished != null) {
                    onFinished.run();
                }
            });
        } catch (RejectedExecutionException e) {
            if (onFinished != null) {
                onFinished.run();
            }
        }
    }

    private void writeChunkInternal(String payload, int chunkIndex, int total) {
        if (isCanceled()) {
            return;
        }
        if (chunkIndex != expectedChunkIndex || total != totalChunks || chunkIndex < 0 || chunkIndex >= totalChunks) {
            markCanceled("Error: Invalid chunk order", true);
            Logger.error(ClientLogMessages.invalidChunkOrder(fileNameDisplay, chunkIndex, totalChunks, expectedChunkIndex));
            return;
        }
        try {
            String base64 = symmetric().decryptMessage(payload);
            byte[] data = Base64.getDecoder().decode(base64);
            fos.write(data);
            receivedBytes += data.length;
            if (receivedBytes > fileSize) {
                markCanceled("Error: Received data exceeds expected size", true);
                Logger.error(ClientLogMessages.receivedTooMuchData(fileNameDisplay, receivedBytes, fileSize));
                return;
            }
            updateProgress(receivedBytes);
            expectedChunkIndex++;
        } catch (Exception e) {
            markCanceled("Error: " + e.getMessage(), true);
            Logger.error(ClientLogMessages.downloadFailedForFile(fileNameDisplay, e), e);
        }
    }

    private void finishInternal() {
        if (isCanceled()) {
            return;
        }
        try {
            fos.flush();
            fos.close();
        } catch (IOException ignored) {
        }
        view.markFinished();
        try {
            sendPacket(new FilePacket(StatusCodes.FILE_ACK, fileId, fileNameEncrypted, fileSize, totalChunks, totalChunks, "", 0, ""));
        } catch (IOException ignored) {
            // no retry by requirement
        }
        Logger.info(ClientLogMessages.downloadFinished(fileNameDisplay));
        shutdownExecutor(false);
    }

    void markCanceled(String reason, boolean notifyServer) {
        markCanceled(reason, notifyServer, true);
    }

    void markCanceled(String reason, boolean notifyServer, boolean advanceQueue) {
        if (!super.markCanceled(reason)) {
            return;
        }
        try {
            fos.close();
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(targetPath);
        } catch (IOException ignored) {
        }
        if (notifyServer) {
            try {
                sendPacket(new FilePacket(StatusCodes.FILE_CANCEL, fileId, fileNameEncrypted, fileSize, 0, totalChunks, "", 0, reason));
            } catch (IOException ignored) {
                // ignore
            }
        }
        onCanceled.accept(fileId, advanceQueue);
        Logger.warn(ClientLogMessages.downloadCanceled(fileNameDisplay, reason));
        shutdownExecutor(true);
    }

    private SymmetricEncryption symmetric() {
        return clientSupplier.get().getSymmetric();
    }

    private void sendPacket(FilePacket packet) throws IOException {
        clientSupplier.get().sendFilePacket(packet);
    }

    private void shutdownExecutor(boolean immediate) {
        if (immediate) {
            ioExecutor.shutdownNow();
        } else {
            ioExecutor.shutdown();
        }
    }

}
