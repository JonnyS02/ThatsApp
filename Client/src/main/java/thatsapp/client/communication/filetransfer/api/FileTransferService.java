package thatsapp.client.communication.filetransfer.api;

import thatsapp.common.FilePacket;

import java.io.File;
import java.util.List;

public interface FileTransferService {

    void setFileSizeBytesLimit(long fileSizeBytesLimit);

    void setFileTransferEnabled(boolean fileTransferEnabled);

    void enqueueUploads(List<File> files);

    void handleIncoming(FilePacket packet);

    void cancelAll();
}
