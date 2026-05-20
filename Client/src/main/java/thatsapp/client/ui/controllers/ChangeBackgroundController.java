package thatsapp.client.ui.controllers;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.stage.FileChooser;
import thatsapp.client.data.DataHolder;
import thatsapp.client.data.DataEncryptor;
import thatsapp.client.messages.ClientAttentionMessages;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;
import thatsapp.client.Main;
import thatsapp.client.ui.FxDialogs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javafx.stage.Stage;

public class ChangeBackgroundController {

    @FXML
    private MFXTextField pathField;

    @FXML
    private MFXButton applyButton;

    private MainController mainController;

    public static void open(MainController mainController) {
        try {
            FxDialogs.showModal(
                    Main.class,
                    "changeBackground.fxml",
                    "Change Background",
                    DataHolder.icon,
                    false,
                    (Scene scene, ChangeBackgroundController controller) -> controller.setMainController(mainController)
            );
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_OPENING_BACKGROUND_WINDOW_PREFIX, e), e);
        }
    }

    public static void applyStoredBackground(MainController controller) {
        if (controller == null) {
            return;
        }
        if (DataHolder.messagePaneBackgroundImageData == null || DataHolder.messagePaneBackgroundImageData.isEmpty()) {
            controller.applyBackgroundStyle("", false);
            return;
        }
        String mime = DataHolder.messagePaneBackgroundImageMimeType == null || DataHolder.messagePaneBackgroundImageMimeType.isEmpty()
                ? "image/png"
                : DataHolder.messagePaneBackgroundImageMimeType;

        String style = "-fx-background-image: url(\"data:" + mime + ";base64," + DataHolder.messagePaneBackgroundImageData + "\");";
        controller.applyBackgroundStyle(style, true);
    }

    public static void resetBackground(MainController controller) {
        DataHolder.messagePaneBackgroundImageSource = "";
        DataHolder.messagePaneBackgroundImageData = "";
        DataHolder.messagePaneBackgroundImageMimeType = "";
        if (controller != null) {
            controller.applyBackgroundStyle("", false);
        }
    }

    @FXML
    private void initialize() {
        String savedSource = DataHolder.messagePaneBackgroundImageSource;
        if (savedSource != null && !savedSource.isBlank() && isExistingFile(savedSource)) {
            pathField.setText(savedSource);
        }
    }

    @FXML
    private void browse() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Image File");
        String currentPath = pathField.getText();
        if (currentPath != null && !currentPath.isBlank()) {
            File initial = new File(currentPath);
            if (initial.exists() && initial.isFile()) {
                chooser.setInitialDirectory(initial.getParentFile());
            }
        }
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Image files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        File selected = chooser.showOpenDialog(pathField.getScene().getWindow());
        if (selected != null) {
            pathField.setText(selected.getAbsolutePath());
            if (applyNewBackground(selected.getAbsolutePath())) {
                close();
            }
        }
    }

    @FXML
    private void apply() {
        String source = pathField.getText() == null ? "" : pathField.getText().trim();
        if (source.isEmpty()) {
            AttentionController.open(false, ClientAttentionMessages.PLEASE_SELECT_IMAGE_FILE);
            return;
        }
        if (applyNewBackground(source)) {
            close();
        }
    }

    @FXML
    private void remove() {
        resetBackground(mainController);
        DataEncryptor.saveEncrypted();
        close();
    }

    @FXML
    private void close() {
        Stage stage = (Stage) applyButton.getScene().getWindow();
        stage.close();
    }

    private boolean applyNewBackground(String source) {
        String trimmed = source.trim();
        byte[] data = loadImageBytes(trimmed);
        if (data == null || data.length == 0) {
            AttentionController.open(false, ClientAttentionMessages.PLEASE_SELECT_EXISTING_IMAGE_FILE);
            Logger.warn(ClientLogMessages.couldNotLoadImageForBackground(trimmed));
            return false;
        }
        String mime = detectMime(trimmed);
        String base64 = Base64.getEncoder().encodeToString(data);
        DataHolder.messagePaneBackgroundImageSource = trimmed;
        DataHolder.messagePaneBackgroundImageData = base64;
        DataHolder.messagePaneBackgroundImageMimeType = mime;
        applyStoredBackground(mainController);
        DataEncryptor.saveEncrypted();
        return true;
    }

    private byte[] loadImageBytes(String source) {
        try {
            File file = new File(source);
            if (file.exists() && file.isFile()) {
                Path path = file.toPath();
                return Files.readAllBytes(path);
            }
        } catch (Exception e) {
            Logger.warn(ClientLogMessages.failedToLoadImage(source, e));
        }
        return null;
    }

    static String detectMime(String source) {
        String lower = source.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }

    private void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    static boolean isExistingFile(String source) {
        if (source == null || source.trim().isEmpty()) {
            return false;
        }
        File file = new File(source);
        return file.exists() && file.isFile();
    }
}
