package thatsapp.client.ui.controllers;

import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import thatsapp.client.data.DataHolder;
import thatsapp.client.data.DataEncryptor;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;
import thatsapp.client.Main;
import thatsapp.client.ui.FxDialogs;

import javafx.stage.Stage;

public class ChangePasswordController {

    @FXML
    private MFXTextField passwordField;

    @FXML
    private MFXTextField repeatPasswordField;

    public static void open() {
        try {
            FxDialogs.showModal(
                    Main.class,
                    "changePassword.fxml",
                    "Change Password",
                    DataHolder.icon,
                    false,
                    (Scene scene, ChangePasswordController controller) -> scene.setOnKeyPressed(e -> {
                        if (e.getCode().getName().equals("Enter")) {
                            controller.changePassword();
                        }
                    })
            );
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_OPENING_CHANGE_WINDOW_PREFIX, e), e);
        }
    }

    @FXML
    private void changePassword() {
        if (LoginController.validatePassword(passwordField, repeatPasswordField)) {
            return;
        }
        DataHolder.password = passwordField.getText();
        DataEncryptor.saveEncrypted();
        close();
    }

    @FXML
    private void close() {
        Stage stage = (Stage) passwordField.getScene().getWindow();
        stage.close();
    }
}
