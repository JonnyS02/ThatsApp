package thatsapp.client.communication.filetransfer.common;

import thatsapp.client.communication.filetransfer.api.FileTransferView;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractTransferTask {

    private final long totalBytes;
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    protected FileTransferView view;

    protected AbstractTransferTask(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public boolean isCanceled() {
        return canceled.get();
    }

    protected boolean markCanceled(String reason) {
        if (!canceled.compareAndSet(false, true)) {
            return false;
        }
        if (view != null) {
            view.markCanceled(reason);
        }
        return true;
    }

    protected void updateProgress(long doneBytes) {
        if (view == null) {
            return;
        }
        double progress = totalBytes == 0 ? 1.0 : Math.min(1.0, (double) doneBytes / totalBytes);
        view.updateProgress(progress, AbstractFileTransferService.formatProgress(doneBytes, totalBytes));
    }

    protected void setView(FileTransferView view) {
        this.view = view;
    }
}
