package thatsapp.server.ui.controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import thatsapp.common.Logger;
import thatsapp.server.Main;
import thatsapp.server.messages.ServerLogMessages;
import thatsapp.server.ui.FxDialogs;

import java.util.Objects;

public class AttentionController {

    private static final int BASE_VISIBLE_LINES = 2;
    private static final double HEIGHT_PER_EXTRA_LINE = 10.0;

    @FXML
    private Label messageLabel;

    public static String attentionMessage = "";

    public static void open(String attentionMessage) {
        AttentionController.attentionMessage = attentionMessage;
        try {
            Image icon = new Image(Objects.requireNonNull(Main.class.getResourceAsStream("/img/icon.png")));
            FxDialogs.showModal(Main.class, "attention.fxml", "Attention", icon, false, null);
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_OPENING_ATTENTION_WINDOW_PREFIX, e), e);
        }
    }

    @FXML
    private void initialize() {
        String message = AttentionController.attentionMessage;
        messageLabel.setWrapText(true);
        messageLabel.setText(message);
        Platform.runLater(() -> applyExtraHeight(message));
        AttentionController.attentionMessage = "";
    }

    @FXML
    private void close() {
        Stage stage = (Stage) messageLabel.getScene().getWindow();
        stage.close();
    }

    private void applyExtraHeight(String message) {
        Stage stage = (Stage) messageLabel.getScene().getWindow();
        int lineCount = countMessageLines(message);
        int extraLines = Math.max(0, lineCount - BASE_VISIBLE_LINES);
        stage.setHeight(stage.getHeight() + extraLines * HEIGHT_PER_EXTRA_LINE);
    }

    private int countMessageLines(String message) {
        String normalized = message.replace("\r", "");
        long breaks = normalized.chars().filter(ch -> ch == '\n').count();
        return (int) breaks + 1;
    }
}
