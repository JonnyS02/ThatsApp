package thatsapp.client.ui.controllers;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXPasswordField;
import io.github.palexdev.materialfx.controls.MFXTextField;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import thatsapp.client.Main;
import thatsapp.client.data.DataHolder;
import thatsapp.client.data.DataEncryptor;
import thatsapp.client.messages.ClientAttentionMessages;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.ChatMessagePayload;
import thatsapp.common.Logger;
import thatsapp.client.ui.FxDialogs;

import java.net.MalformedURLException;
import javafx.stage.Stage;

public class ConfigController {

    @FXML
    private MFXTextField urlField;

    @FXML
    private MFXTextField sessionField;

    @FXML
    private MFXToggleButton autoCreateSwitch;

    @FXML
    private MFXTextField hostIpField;

    @FXML
    private MFXTextField portField;

    @FXML
    private MFXButton applyButton;

    @FXML
    private MFXTextField accessKeyField;

    @FXML
    private MFXPasswordField roomSecretField;

    public static void open() {
        String fxml = DataHolder.connectionType.equals(DataHolder.connectionTypes[0]) ? "desktop.fxml" : "web.fxml";
        try {
            FxDialogs.showModal(
                    Main.class,
                    "config/" + fxml,
                    "Connection Settings",
                    DataHolder.icon,
                    false,
                    (Scene scene, ConfigController controller) -> scene.setOnKeyPressed(e -> {
                        if (e.getCode().getName().equals("Enter")) {
                            controller.applyChanges();
                        }
                    })
            );
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_OPENING_CONFIGURATION_WINDOW_PREFIX, e), e);
        }
    }

    @FXML
    private void initialize() {
        if (DataHolder.connectionType.equals(DataHolder.connectionTypes[0])) {
            hostIpField.setText(DataHolder.hostIp);
            portField.setText(String.valueOf(DataHolder.port));
            accessKeyField.setText(DataHolder.desktopserverAccessKey);
            roomSecretField.setText(DataHolder.desktopserverRoomSecret);
        } else {
            urlField.setText(DataHolder.url);
            sessionField.setText(DataHolder.sessionName);
            autoCreateSwitch.setSelected(DataHolder.autoCreate);
            accessKeyField.setText(DataHolder.webserverAccessKey);
            roomSecretField.setText(DataHolder.webserverRoomSecret);
        }
    }

    @FXML
    private void applyChanges() {
        if (!validate()) {
            return;
        }
        if (DataHolder.connectionType.equals(DataHolder.connectionTypes[0])) {
            DataHolder.hostIp = hostIpField.getText();
            DataHolder.port = Integer.parseInt(portField.getText());
            DataHolder.desktopserverAccessKey = accessKeyField.getText();
            DataHolder.desktopserverRoomSecret = roomSecretField.getText();
            Logger.info(ClientLogMessages.appliedDesktopConfiguration(DataHolder.hostIp, DataHolder.port));
        } else {
            DataHolder.url = urlField.getText();
            DataHolder.sessionName = sessionField.getText();
            DataHolder.autoCreate = autoCreateSwitch.isSelected();
            DataHolder.webserverAccessKey = accessKeyField.getText();
            DataHolder.webserverRoomSecret = roomSecretField.getText();
            Logger.info(ClientLogMessages.appliedWebConfiguration(DataHolder.url, DataHolder.sessionName, DataHolder.autoCreate));
        }
        DataEncryptor.saveEncrypted();
        close();
    }

    private boolean validate() {
        String attentionMessage = DataHolder.connectionType.equals(DataHolder.connectionTypes[0])
                ? validateDesktopConfig(hostIpField.getText(), portField.getText(), roomSecretField.getText())
                : validateWebConfig(urlField.getText(), sessionField.getText(), roomSecretField.getText());

        if (!attentionMessage.isEmpty()) {
            AttentionController.open(false, attentionMessage);
            Logger.warn(ClientLogMessages.configurationValidationFailed(attentionMessage));
            return false;
        }
        return true;
    }

    static String validateDesktopConfig(String hostIp, String port, String roomSecret) {
        StringBuilder attentionMessage = new StringBuilder();
        if (hostIp.isEmpty()) {
            attentionMessage.append(ClientAttentionMessages.HOST_IP_CANNOT_BE_EMPTY).append("\n");
        } else if (!isValidIpAddress(hostIp)) {
            attentionMessage.append(ClientAttentionMessages.INVALID_IP_ADDRESS).append("\n");
        }
        if (port.isEmpty()) {
            attentionMessage.append(ClientAttentionMessages.PORT_CANNOT_BE_EMPTY).append("\n");
        } else {
            try {
                long portNumber = Long.parseLong(port);
                if (portNumber < 1 || portNumber > 65535) {
                    attentionMessage.append(ClientAttentionMessages.PORT_MUST_BE_BETWEEN_1_AND_65535).append("\n");
                }
            } catch (NumberFormatException e) {
                attentionMessage.append(ClientAttentionMessages.PORT_MUST_BE_A_VALID_NUMBER).append("\n");
            }
        }
        if (!roomSecret.isEmpty() && roomSecret.length() < 16) {
            attentionMessage.append(ClientAttentionMessages.ROOM_SECRET_MIN_16_WHEN_PROVIDED).append("\n");
        }
        return attentionMessage.toString();
    }

    static String validateWebConfig(String url, String session, String roomSecret) {
        StringBuilder attentionMessage = new StringBuilder();
        if (url.isEmpty()) {
            attentionMessage.append(ClientAttentionMessages.URL_CANNOT_BE_EMPTY).append("\n");
        } else if (!isValidUrl(url)) {
            attentionMessage.append(ClientAttentionMessages.INVALID_URL).append("\n");
        }
        if (session.isEmpty()) {
            attentionMessage.append(ClientAttentionMessages.SESSION_NAME_CANNOT_BE_EMPTY).append("\n");
        } else if (session.contains("/")) {
            attentionMessage.append(ClientAttentionMessages.SESSION_NAME_MUST_NOT_CONTAIN_SLASH).append("\n");
        } else if (ChatMessagePayload.countCharacters(session) > 64) {
            attentionMessage.append(ClientAttentionMessages.SESSION_NAME_MAX_64).append("\n");
        }
        if (!roomSecret.isEmpty() && roomSecret.length() < 16) {
            attentionMessage.append(ClientAttentionMessages.ROOM_SECRET_MIN_16_WHEN_PROVIDED).append("\n");
        }
        return attentionMessage.toString();
    }

    static boolean isValidIpAddress(String ip) {
        if (ip.equals("localhost")) {
            return true;
        }
        String ipv4Pattern = "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
        String ipv6Pattern = "(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|::|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6}))";
        return ip.matches(ipv4Pattern) || ip.matches(ipv6Pattern);
    }

    static boolean isValidUrl(String url) {
        try {
            new java.net.URL(url);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    @FXML
    private void close() {
        Stage stage = (Stage) applyButton.getScene().getWindow();
        stage.close();
    }
}
