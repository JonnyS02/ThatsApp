package thatsapp.client.communication.filetransfer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import thatsapp.client.communication.SymmetricEncryption;
import thatsapp.client.communication.filetransfer.api.FileTransferUi;
import thatsapp.client.communication.filetransfer.api.FileTransferView;
import thatsapp.client.communication.transport.CommunicationClient;
import thatsapp.client.communication.transport.CommunicationClientListener;
import thatsapp.client.communication.transport.Config;
import thatsapp.common.FilePacket;

public final class FileTransferTestSupport {

    private FileTransferTestSupport() {
    }

    public static final class RecordingClient implements CommunicationClient {
        public final List<FilePacket> packets = new ArrayList<>();

        private final SymmetricEncryption symmetric;

        public RecordingClient(SymmetricEncryption symmetric) {
            this.symmetric = symmetric;
        }

        @Override
        public void connect(Config config, CommunicationClientListener listener, Executor callbackExecutor) {
        }

        @Override
        public void requestCancel() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public SymmetricEncryption getSymmetric() {
            return symmetric;
        }

        @Override
        public void sendMessage(String message) {
        }

        @Override
        public void sendTyping(long ttlMs) {
        }

        @Override
        public void sendFilePacket(FilePacket packet) throws IOException {
            packets.add(packet);
        }
    }

    public static final class RecordingUi implements FileTransferUi {
        private final Path downloadDirectory;
        private final boolean autoDownload;
        private final Map<String, RecordingView> incomingViews = new HashMap<>();
        private final Map<String, RecordingView> outgoingViews = new HashMap<>();

        public String lastError = "";
        public boolean incomingShown;
        public String lastIncomingFileName = "";
        public String lastSenderName = "";

        public RecordingUi(Path downloadDirectory, boolean autoDownload) {
            this.downloadDirectory = downloadDirectory;
            this.autoDownload = autoDownload;
        }

        @Override
        public void showError(String message) {
            lastError = message;
        }

        @Override
        public boolean autoDownload() {
            return autoDownload;
        }

        @Override
        public Path downloadDirectory() {
            return downloadDirectory;
        }

        @Override
        public FileTransferView showOutgoing(String fileName) {
            return outgoingView(fileName);
        }

        @Override
        public FileTransferView showIncoming(String fileName, String senderName) {
            incomingShown = true;
            lastIncomingFileName = fileName;
            lastSenderName = senderName;
            return incomingView(fileName);
        }

        public RecordingView incomingView(String fileName) {
            return incomingViews.computeIfAbsent(fileName, ignored -> new RecordingView());
        }

        public RecordingView outgoingView(String fileName) {
            return outgoingViews.computeIfAbsent(fileName, ignored -> new RecordingView());
        }
    }

    public static final class RecordingView implements FileTransferView {
        private final CountDownLatch canceledLatch = new CountDownLatch(1);
        private final CountDownLatch finishedLatch = new CountDownLatch(1);

        public Runnable downloadPrompt = () -> {
        };
        public Runnable cancelAction = () -> {
        };
        public String progressLabel = "";
        public String canceledReason = "";
        public boolean finished;
        public boolean promptShown;

        @Override
        public void showDownloadPrompt(Runnable action) {
            promptShown = true;
            downloadPrompt = action;
        }

        @Override
        public void setCancelAction(Runnable action) {
            cancelAction = action;
        }

        @Override
        public void updateProgress(double progress, String label) {
            progressLabel = label;
        }

        @Override
        public void markFinished() {
            finished = true;
            finishedLatch.countDown();
        }

        @Override
        public void markCanceled(String reason) {
            canceledReason = reason;
            canceledLatch.countDown();
        }

        public boolean awaitCanceled() throws InterruptedException {
            return canceledLatch.await(2, TimeUnit.SECONDS);
        }

        public boolean awaitFinished() throws InterruptedException {
            return finishedLatch.await(5, TimeUnit.SECONDS);
        }
    }
}
