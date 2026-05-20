package thatsapp.client.communication.filetransfer.common;

import thatsapp.client.communication.filetransfer.api.FileTransferService;
import thatsapp.client.communication.filetransfer.api.FileTransferUi;
import thatsapp.client.messages.ClientAttentionMessages;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared helpers for file transfer services.
 */
public abstract class AbstractFileTransferService implements FileTransferService {

    protected static final int MAX_FILES = 10;
    public static final String STATUS_START_DOWNLOAD = "Start Download";
    public static final String STATUS_WAITING_FOR_PREVIOUS_UPLOAD = "Waiting for previous upload...";
    public static final String STATUS_WAITING_FOR_PREVIOUS_DOWNLOADS = "Waiting for previous downloads...";
    public static final String REASON_REMOVED_FROM_QUEUE = "Removed from queue";
    public static final String REASON_DISCONNECTED = "Disconnected";
    public static final String REASON_UPLOAD_CANCELED = "Upload canceled";
    public static final String REASON_DOWNLOAD_CANCELED = "Download canceled";
    public static final String REASON_CANCELED_PREFIX = "Canceled: ";
    public static final String REASON_DOWNLOAD_REQUEST_FAILED = "Download request failed";
    public static final String REASON_DOWNLOAD_COULD_NOT_BE_STARTED = "Download could not be started";
    public static final String REASON_FILE_EXCEEDS_LIMIT = "File exceeds limit";
    public static final String REASON_FILE_TRANSFER_DISABLED = "File transfer disabled";
    private static final String DEFAULT_DOWNLOAD_FILE_NAME = "download";

    protected final FileTransferUi ui;
    protected long fileSizeBytesLimit;
    protected boolean fileTransferEnabled = true;

    protected AbstractFileTransferService(FileTransferUi ui, long defaultFileSizeBytesLimit) {
        this.ui = ui;
        this.fileSizeBytesLimit = defaultFileSizeBytesLimit;
    }

    @Override
    public void setFileSizeBytesLimit(long fileSizeBytesLimit) {
        this.fileSizeBytesLimit = fileSizeBytesLimit;
    }

    @Override
    public void setFileTransferEnabled(boolean fileTransferEnabled) {
        this.fileTransferEnabled = fileTransferEnabled;
    }

    protected static long toMb(long bytes) {
        return Math.round(bytes / 1024.0 / 1024.0);
    }

    protected static String formatProgress(long done, long total) {
        long percent = total == 0 ? 100 : Math.round(done * 100.0 / total);
        long mbDone = Math.round(done / 1024.0 / 1024.0);
        long mbTotal = Math.round(total / 1024.0 / 1024.0);
        return String.format("%d%% (%d / %d MB)", percent, mbDone, mbTotal);
    }

    protected boolean validateSelection(List<File> files) {
        if (!fileTransferEnabled) {
            ui.showError(ClientAttentionMessages.FILE_TRANSFER_DISABLED);
            return true;
        }
        if (files == null || files.isEmpty()) {
            return true;
        }
        if (files.size() > MAX_FILES) {
            ui.showError(ClientAttentionMessages.MAX_FILES_LIMIT);
            return true;
        }
        return false;
    }

    protected boolean exceedsFileLimit(long fileSize) {
        return fileSizeBytesLimit > 0 && fileSize > fileSizeBytesLimit;
    }

    protected List<File> filterUploadFiles(List<File> files) {
        if (validateSelection(files)) {
            return Collections.emptyList();
        }
        boolean rejected = false;
        List<File> validFiles = new ArrayList<>();
        for (File file : files) {
            long size = file.length();
            if (exceedsFileLimit(size)) {
                if (!rejected) {
                    ui.showError(ClientAttentionMessages.fileExceedsLimit(toMb(fileSizeBytesLimit)));
                    rejected = true;
                }
                Logger.warn(ClientLogMessages.rejectedFile(file.getName(), size, fileSizeBytesLimit));
                continue;
            }
            validFiles.add(file);
        }
        return validFiles;
    }

    protected Path buildTargetPath(String fileName) {
        Path baseDir = ui.downloadDirectory();
        String safeName = sanitizeFileName(fileName);
        Path target = baseDir.resolve(safeName);
        if (!Files.exists(target)) {
            return target;
        }
        String name = safeName;
        String ext = "";
        int dot = safeName.lastIndexOf('.');
        if (dot > 0) {
            name = safeName.substring(0, dot);
            ext = safeName.substring(dot);
        }
        int counter = 1;
        while (Files.exists(target)) {
            target = baseDir.resolve(name + " (" + counter + ")" + ext);
            counter++;
        }
        return target;
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return DEFAULT_DOWNLOAD_FILE_NAME;
        }
        String trimmed = fileName.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_DOWNLOAD_FILE_NAME;
        }
        String baseName = trimmed;
        try {
            Path name = Path.of(trimmed).getFileName();
            if (name != null) {
                baseName = name.toString();
            }
        } catch (Exception ignored) {
            baseName = trimmed;
        }
        baseName = baseName.replace("/", "_").replace("\\", "_");
        if (baseName.isBlank() || ".".equals(baseName) || "..".equals(baseName)) {
            return DEFAULT_DOWNLOAD_FILE_NAME;
        }
        return baseName;
    }
}
