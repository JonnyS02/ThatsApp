package thatsapp.client.communication.filetransfer.api;

public interface FileTransferView {

    void showDownloadPrompt(Runnable action);

    void setCancelAction(Runnable action);

    void updateProgress(double progress, String label);

    void markFinished();

    void markCanceled(String reason);
}
