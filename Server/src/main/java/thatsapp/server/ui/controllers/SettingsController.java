package thatsapp.server.ui.controllers;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import thatsapp.common.ServerRules;
import thatsapp.common.Logger;
import thatsapp.server.Main;
import thatsapp.server.messages.ServerAttentionMessages;
import thatsapp.server.messages.ServerLogMessages;
import thatsapp.server.ui.FxDialogs;

import java.util.Objects;
import javafx.stage.Stage;

public class SettingsController {

    @FXML
    private MFXTextField userLimitField;

    @FXML
    private MFXTextField fileCountLimitField;

    @FXML
    private MFXTextField fileSizeLimitField;

    @FXML
    private MFXTextField messageCharacterLimitField;

    @FXML
    private MFXButton applyButton;

    private MainController mainController;

    public static void open(MainController mainController) {
        try {
            Image icon = new Image(Objects.requireNonNull(Main.class.getResourceAsStream("/img/icon.png")));
            FxDialogs.showModal(
                    Main.class,
                    "settings.fxml",
                    "Settings",
                    icon,
                    false,
                    (Scene scene, SettingsController controller) -> controller.init(mainController)
            );
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_OPENING_SETTINGS_WINDOW_PREFIX, e), e);
        }
    }

    @FXML
    private void applyChanges() {
        String attentionMessage = validateLimits();
        if (!attentionMessage.isEmpty()) {
            AttentionController.open(attentionMessage);
            Logger.warn(ServerLogMessages.settingsValidationFailed(attentionMessage));
            return;
        }
        try {
            int userLimit = Integer.parseInt(userLimitField.getText());
            int fileCountLimit = Integer.parseInt(fileCountLimitField.getText());
            long fileSizeBytesLimitMb = Long.parseLong(fileSizeLimitField.getText());
            int messageCharacterLimit = Integer.parseInt(messageCharacterLimitField.getText());
            long fileSizeBytesLimit = Math.multiplyExact(fileSizeBytesLimitMb, 1024 * 1024);
            ServerRules rules = new ServerRules(userLimit, fileCountLimit, fileSizeBytesLimit, messageCharacterLimit);
            mainController.updateServerRules(rules);
            close();
        } catch (ArithmeticException e) {
            AttentionController.open(ServerAttentionMessages.FILE_SIZE_LIMIT_TOO_LARGE);
        }
    }

    @FXML
    private void close() {
        Stage stage = (Stage) applyButton.getScene().getWindow();
        stage.close();
    }

    private void init(MainController mainController) {
        this.mainController = mainController;
        populateFields(mainController.getServerRules());
    }

    private void populateFields(ServerRules rules) {
        userLimitField.setText(String.valueOf(rules.userLimit()));
        fileCountLimitField.setText(String.valueOf(rules.fileCountLimit()));
        fileSizeLimitField.setText(String.valueOf(rules.fileSizeBytesLimitInMb()));
        messageCharacterLimitField.setText(String.valueOf(rules.messageCharacterLimit()));
    }

    private String validateLimits() {
        return validateLimits(userLimitField.getText(), fileCountLimitField.getText(), fileSizeLimitField.getText(), messageCharacterLimitField.getText());
    }

    static String validateLimits(String userLimitText, String fileCountLimitText, String fileSizeLimitText, String messageCharacterLimitText) {
        StringBuilder attentionMessage = new StringBuilder();
        String[] values = {userLimitText, fileCountLimitText, fileSizeLimitText, messageCharacterLimitText};
        String[] labels = {"User limit", "File count limit", "File size limit", "Message character limit"};
        long[] minimumValues = {0, -1, 0, 0};
        for (int i = 0; i < values.length; i++) {
            String valueText = values[i];
            if (valueText.isEmpty()) {
                attentionMessage.append(ServerAttentionMessages.fieldCannotBeEmpty(labels[i])).append("\n");
                continue;
            }
            try {
                long value = Long.parseLong(valueText);
                if (value < minimumValues[i]) {
                    attentionMessage.append(i == 1
                            ? ServerAttentionMessages.fieldMustBeAtLeastNegativeOne(labels[i])
                            : ServerAttentionMessages.fieldMustBeAtLeastZero(labels[i])).append("\n");
                    continue;
                }
                if (i != 2 && value > Integer.MAX_VALUE) {
                    attentionMessage.append(ServerAttentionMessages.fieldMustBeValidNumber(labels[i])).append("\n");
                }
            } catch (NumberFormatException e) {
                attentionMessage.append(ServerAttentionMessages.fieldMustBeValidNumber(labels[i])).append("\n");
            }
        }
        return attentionMessage.toString();
    }
}
