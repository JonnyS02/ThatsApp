package thatsapp.client.communication.filetransfer.desktop;

import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.filetransfer.api.FileTransferUi;
import thatsapp.client.communication.filetransfer.common.AbstractFileTransferService;
import thatsapp.client.communication.filetransfer.common.AbstractTransferTask;
import thatsapp.client.communication.transport.CommunicationClient;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;
import thatsapp.common.FilePacket;
import thatsapp.common.StatusCodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class UploadTask extends AbstractTransferTask implements Runnable {
    private final File file;
    private final String fileId = UUID.randomUUID().toString();
    private final long fileSize;
    private final int totalChunks;
    private final Supplier<CommunicationClient> clientSupplier;
    private final FileTransferUi ui;
    private final Consumer<UploadTask> removeFromQueue;
    private final Consumer<String> onFinished;
    private final int chunkSize;

    private String encryptedFileName;

    UploadTask(
            File file,
            int chunkSize,
            Supplier<CommunicationClient> clientSupplier,
            FileTransferUi ui,
            Consumer<UploadTask> removeFromQueue,
            Consumer<String> onFinished
    ) {
        super(file.length());
        this.file = file;
        this.chunkSize = chunkSize;
        this.clientSupplier = clientSupplier;
        this.ui = ui;
        this.removeFromQueue = removeFromQueue;
        this.onFinished = onFinished;
        this.fileSize = file.length();
        this.totalChunks = (int) Math.ceil((double) fileSize / chunkSize);
    }

    String fileId() {
        return fileId;
    }

    void showQueuedWaiting() {
        ensureView();
        view.updateProgress(0, AbstractFileTransferService.STATUS_WAITING_FOR_PREVIOUS_UPLOAD);
        view.setCancelAction(() -> {
            removeFromQueue.accept(this);
            markCanceled(AbstractFileTransferService.REASON_REMOVED_FROM_QUEUE);
        });
    }

    void start() {
        ensureView();
        view.setCancelAction(() -> markCanceled(AbstractFileTransferService.REASON_UPLOAD_CANCELED));
        Logger.info(ClientLogMessages.uploadStarted(file.getName(), fileSize));
        new Thread(this, "upload-" + file.getName()).start();
    }

    @Override
    public void run() {
        String payloadName = encryptedFileName();
        try (FileInputStream fis = new FileInputStream(file)) {
            sendPacket(new FilePacket(StatusCodes.FILE_META, fileId, payloadName, fileSize, -1, totalChunks, "", 0, ""));
            byte[] buffer = new byte[chunkSize];
            int read;
            int chunkIndex = 0;
            long sentBytes = 0;
            while ((read = fis.read(buffer)) != -1) {
                if (isCanceled()) {
                    notifyCancel();
                    view.markCanceled(AbstractFileTransferService.REASON_UPLOAD_CANCELED);
                    return;
                }
                String base64 = Base64.getEncoder().encodeToString(read == buffer.length ? buffer : Arrays.copyOf(buffer, read));
                String encrypted = symmetric().encryptMessage(base64);
                sendPacket(new FilePacket(StatusCodes.FILE_CHUNK, fileId, payloadName, fileSize, chunkIndex, totalChunks, encrypted, 0, ""));
                sentBytes += read;
                chunkIndex++;
                updateProgress(sentBytes);
            }
            sendPacket(new FilePacket(StatusCodes.FILE_COMPLETE, fileId, payloadName, fileSize, totalChunks, totalChunks, "", 0, ""));
            view.markFinished();
            Logger.info(ClientLogMessages.uploadFinished(file.getName()));
        } catch (Exception e) {
            view.markCanceled("Error: " + e.getMessage());
            Logger.error(ClientLogMessages.uploadFailedForFile(file.getName(), e), e);
        } finally {
            onFinished.accept(fileId);
        }
    }

    @Override
    protected boolean markCanceled(String reason) {
        if (!super.markCanceled(reason)) {
            return false;
        }
        Logger.warn(ClientLogMessages.uploadCanceled(file.getName(), reason));
        return true;
    }

    private void notifyCancel() throws IOException {
        String payloadName = encryptedFileName();
        sendPacket(new FilePacket(StatusCodes.FILE_CANCEL, fileId, payloadName, fileSize, 0, totalChunks, "", 0, AbstractFileTransferService.REASON_UPLOAD_CANCELED));
    }

    private void ensureView() {
        if (this.view == null) {
            setView(ui.showOutgoing(file.getName()));
        }
    }

    private SymmetricEncryption symmetric() {
        return clientSupplier.get().getSymmetric();
    }

    private String encryptedFileName() {
        if (encryptedFileName != null) {
            return encryptedFileName;
        }
        encryptedFileName = symmetric().encryptFileName(file.getName());
        return encryptedFileName;
    }

    private void sendPacket(FilePacket packet) throws IOException {
        clientSupplier.get().sendFilePacket(packet);
    }

}
