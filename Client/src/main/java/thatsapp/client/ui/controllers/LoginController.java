package thatsapp.client.ui.controllers;

import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.stage.Stage;
import thatsapp.client.data.DataEncryptor;
import thatsapp.client.data.DataHolder;
import thatsapp.client.data.Result;
import thatsapp.client.messages.ClientAttentionMessages;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;
import thatsapp.client.Main;
import thatsapp.client.ui.FxDialogs;

public class LoginController {

    @FXML
    private MFXTextField passwordField;

    @FXML
    private MFXTextField repeatPasswordField;

    public static void open(Stage stage) {
        try {
            DataEncryptor.refreshFileExists();
            String fxml = "standard.fxml";
            if (!DataHolder.fileExists) {
                fxml = "new.fxml";
            }
            FxDialogs.showOnStage(
                    stage,
                    Main.class,
                    "login/" + fxml,
                    (Stage s, LoginController controller) -> {
                        s.setTitle(DataHolder.fileExists ? DataHolder.title + " Login" : DataHolder.title + " Setup");
                        s.getIcons().add(DataHolder.icon);
                        s.setResizable(false);
                    },
                    (Scene scene, LoginController controller) -> scene.setOnKeyPressed(e -> {
                        if (e.getCode().getName().equals("Enter")) {
                            controller.login();
                        }
                    })
            );
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_OPENING_LOGIN_WINDOW_PREFIX, e), e);
        }
    }

    public static boolean validatePassword(MFXTextField passwordField, MFXTextField repeatPasswordField) {
        String message = validatePasswordMessage(passwordField.getText(), repeatPasswordField.getText());
        if (message.isEmpty()) {
            return false;
        }
        Logger.warn(ClientLogMessages.passwordValidationFailed(message));
        AttentionController.open(false, message);
        return true;
    }

    static String validatePasswordMessage(String password, String repeatPassword) {
        StringBuilder attentionMessage = new StringBuilder();
        if (password.isEmpty()) {
            attentionMessage.append(ClientAttentionMessages.PLEASE_ENTER_PASSWORD).append("\n");
        }
        if (repeatPassword.isEmpty()) {
            attentionMessage.append(ClientAttentionMessages.PLEASE_REPEAT_PASSWORD).append("\n");
        }
        if (!password.isEmpty() && !repeatPassword.isEmpty() && !password.equals(repeatPassword)) {
            attentionMessage.append(ClientAttentionMessages.PASSWORDS_DO_NOT_MATCH).append("\n");
        }
        return attentionMessage.toString().trim();
    }

    @FXML
    private void login() {
        if (!DataHolder.fileExists) {
            setNewPassword();
        } else {
            loginWithPassword();
        }
    }

    @FXML
    private void resetData() {
        AttentionController.open(true, ClientAttentionMessages.RESET_ALL_DATA_CONFIRMATION);
        if (DataHolder.attentionConfirmed) {
            DataEncryptor.deleteFile();
            Stage stage = (Stage) passwordField.getScene().getWindow();
            stage.close();
            open(stage);
            DataHolder.attentionConfirmed = false;
            Logger.info(ClientLogMessages.SETTINGS_DATA_RESET_BY_USER);
        }
    }

    @FXML
    private void close() {
        System.exit(0);
    }

    private void setNewPassword() {
        if (validatePassword(passwordField, repeatPasswordField)) {
            return;
        }
        DataHolder.password = passwordField.getText();
        Stage stage = (Stage) passwordField.getScene().getWindow();
        stage.close();
        MainController.open();
    }

    private void loginWithPassword() {
        if (passwordField.getText().isEmpty()) {
            Logger.warn(ClientLogMessages.LOGIN_FAILED_PASSWORD_EMPTY);
            AttentionController.open(false, ClientAttentionMessages.PLEASE_ENTER_PASSWORD);
            return;
        }
        DataHolder.password = passwordField.getText();
        Result result = DataEncryptor.loadEncrypted();
        switch (result) {
            case SUCCESS -> {
                Stage stage = (Stage) passwordField.getScene().getWindow();
                stage.close();
                Logger.info(ClientLogMessages.LOGIN_SUCCESSFUL);
                MainController.open();
            }
            case INVALID_CONTENT -> {
                Logger.warn(ClientLogMessages.LOGIN_FAILED_WRONG_PASSWORD_OR_CORRUPTED_FILE);
                AttentionController.open(false, ClientAttentionMessages.WRONG_PASSWORD_OR_FILE_CORRUPTED);
            }
        }
    }
}
