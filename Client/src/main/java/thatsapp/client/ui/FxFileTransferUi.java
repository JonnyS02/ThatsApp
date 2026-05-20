package thatsapp.client.ui;

import io.github.palexdev.materialfx.controls.MFXProgressBar;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.kordamp.ikonli.javafx.FontIcon;
import thatsapp.client.communication.filetransfer.api.FileTransferUi;
import thatsapp.client.communication.filetransfer.api.FileTransferView;
import thatsapp.client.ui.controllers.AttentionController;
import thatsapp.client.ui.controllers.MainController;
import thatsapp.client.data.DataHolder;
import thatsapp.client.Main;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public record FxFileTransferUi(MainController controller) implements FileTransferUi {

    @Override
    public void showError(String message) {
        Platform.runLater(() -> AttentionController.open(false, message));
    }

    @Override
    public boolean autoDownload() {
        return DataHolder.autoDownloadFiles;
    }

    @Override
    public Path downloadDirectory() {
        String base = DataHolder.fileDownloadPath;
        if (base == null || base.isBlank()) {
            base = Paths.get(System.getProperty("user.home"), "Desktop").toString();
        }
        Path baseDir = Paths.get(base);
        if (!Files.exists(baseDir)) {
            baseDir = Paths.get(System.getProperty("user.home"));
        }
        return baseDir;
    }

    @Override
    public FileTransferView showOutgoing(String fileName) {
        return create("ownFile.fxml", fileName, null);
    }

    @Override
    public FileTransferView showIncoming(String fileName, String senderName) {
        return create("otherFile.fxml", fileName, senderName);
    }

    private FileTransferView create(String fxml, String fileName, String user) {
        try {
            HBox root = new FXMLLoader(Main.class.getResource("messages/" + fxml)).load();
            Label messageLabel = (Label) root.lookup("#messageLabel");
            Label userLabel = (Label) root.lookup("#userLabel");
            Label progressLabel = (Label) root.lookup("#progressLabel");
            Label timestamp = (Label) root.lookup("#timestamp");
            MFXProgressBar progressBar = (MFXProgressBar) root.lookup("#progressBar");
            FontIcon cancelIcon = (FontIcon) root.lookup("#cancelIcon");
            FontIcon actionIcon = (FontIcon) root.lookup("#actionIcon");
            controller.bindMessageWidth(messageLabel, timestamp);
            messageLabel.setText(fileName);
            if (userLabel != null && user != null) {
                userLabel.setText(user);
                controller.applyShuffledUserNameColor(userLabel, user);
            }
            timestamp.setText(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
            Platform.runLater(() -> {
                controller.addMessageNode(root);
                controller.scrollDown();
            });
            return new FxView(progressLabel, progressBar, cancelIcon, actionIcon);
        } catch (IOException e) {
            throw new IllegalStateException("UI konnte nicht geladen werden: " + e.getMessage(), e);
        }
    }
}
