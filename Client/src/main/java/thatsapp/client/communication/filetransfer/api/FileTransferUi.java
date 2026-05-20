package thatsapp.client.communication.filetransfer.api;

import java.nio.file.Path;

public interface FileTransferUi {

    void showError(String message);

    boolean autoDownload();

    Path downloadDirectory();

    FileTransferView showOutgoing(String fileName);

    FileTransferView showIncoming(String fileName, String senderName);
}
