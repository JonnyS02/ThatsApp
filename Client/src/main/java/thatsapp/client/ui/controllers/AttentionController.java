package thatsapp.client.ui.controllers;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import thatsapp.client.Main;
import thatsapp.client.data.DataHolder;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;
import thatsapp.client.ui.FxDialogs;

import javafx.stage.Stage;

public class AttentionController {

    private static final int BASE_VISIBLE_LINES = 2;
    private static final double HEIGHT_PER_EXTRA_LINE = 10.0;

    @FXML
    private Label messageLabel;

    public static void open(boolean confirm, String attentionMessage) {
        DataHolder.attentionConfirmed = false;
        DataHolder.attentionMessage = attentionMessage;
        String fxml = confirm ? "attention/confirm.fxml" : "attention/standard.fxml";
        try {
            FxDialogs.showModal(Main.class, fxml, "Attention", DataHolder.icon, false, null);
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_OPENING_ATTENTION_WINDOW_PREFIX, e), e);
        }
    }

    @FXML
    private void initialize() {
        String message = DataHolder.attentionMessage;
        messageLabel.setWrapText(true);
        messageLabel.setText(message);
        Platform.runLater(() -> applyExtraHeight(message));
        DataHolder.attentionMessage = "";
    }

    @FXML
    private void confirm() {
        DataHolder.attentionConfirmed = true;
        close();
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
