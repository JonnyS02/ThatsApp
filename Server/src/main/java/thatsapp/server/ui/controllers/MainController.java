package thatsapp.server.ui.controllers;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXPasswordField;
import io.github.palexdev.materialfx.controls.MFXScrollPane;
import io.github.palexdev.materialfx.controls.MFXTextField;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import thatsapp.common.ServerRules;
import thatsapp.server.communication.Server;
import thatsapp.common.Logger;
import thatsapp.server.Main;
import thatsapp.server.ServerSettingsStore;
import thatsapp.server.messages.ServerAttentionMessages;
import thatsapp.server.messages.ServerLogMessages;
import thatsapp.server.ui.FxDialogs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;

public class MainController {

    @FXML
    private Label userCounterLabel;

    @FXML
    private Label userListLabel;

    @FXML
    private Separator statusHBoxSeparator;

    @FXML
    private MFXTextField externalIpField;

    @FXML
    private MFXTextField internalIpField;

    @FXML
    private MFXTextField portField;

    @FXML
    private MFXPasswordField accessKeyField;

    @FXML
    private MFXButton startButton;

    @FXML
    private MFXButton settingsButton;

    @FXML
    private MFXTextField messageInput;

    @FXML
    private MFXButton sendButton;

    @FXML
    private MFXScrollPane scrollPane;

    @FXML
    private VBox messageVBox;

    @FXML
    private FontIcon connectionStatusIcon;

    private Server server;

    public static void open(Stage stage) {
        try {
            FxDialogs.showOnStage(
                    stage,
                    Main.class,
                    "main.fxml",
                    (Stage s, MainController controller) -> {
                        s.setTitle("ThatsApp Server");
                        s.getIcons().add(new Image(Objects.requireNonNull(Main.class.getResourceAsStream("/img/icon.png"))));
                        s.setOnCloseRequest(e -> {
                            controller.persistSettings();
                            System.exit(0);
                        });
                        s.setMinWidth(750);
                        s.setMinHeight(300);
                    },
                    (Scene scene, MainController controller) -> scene.setOnKeyPressed(e -> {
                        if (e.getCode().toString().equals("ENTER")) {
                            controller.sendMessage();
                        }
                    })
            );
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_OPENING_MAIN_WINDOW_PREFIX, e), e);
        }
    }

    @FXML
    private void initialize() {
        externalIpField.setText(getExternalIp());
        internalIpField.setText(getInternalIp());
        server = new Server(this);
        portField.setText(String.valueOf(ServerSettingsStore.loadPort()));
        messageInput.setTextLimit(resolveMessageCharacterLimit(server.getRules()));
    }

    public void started() {
        String message = ServerLogMessages.serverStartedOnPort(portField.getText());
        Logger.info(message);
        startButton.setText("Stop");
        startButton.getStyleClass().remove("button-success");
        startButton.getStyleClass().add("button-danger");
        portField.setEditable(false);
        accessKeyField.setEditable(false);
        sendButton.setDisable(false);
        messageInput.setDisable(false);
        settingsButton.setDisable(true);
        appendMessage(message);
        userCounterLabel.setText("No users connected.");
        updateConnectionStatusIcon(true);
    }

    public void stopped() {
        String message = ServerLogMessages.serverStopped();
        Logger.info(message);
        startButton.setText("Start");
        startButton.getStyleClass().remove("button-danger");
        startButton.getStyleClass().add("button-success");
        portField.setEditable(true);
        accessKeyField.setEditable(true);
        sendButton.setDisable(true);
        messageInput.setDisable(true);
        settingsButton.setDisable(false);
        appendMessage(message);
        userCounterLabel.setText("Offline");
        userListLabel.setVisible(false);
        statusHBoxSeparator.setVisible(false);
        updateConnectionStatusIcon(false);
    }

    public void appendMessage(String message) {
        String time = java.time.LocalTime.now().toString().substring(0, 8);
        messageVBox.getChildren().add(new Label("[" + time + "] " + message));
        scrollDown();
    }

    public void updateUserCounter(Map<Integer, String> clientNames) {
        if (!server.isRunning()) {
            return;
        }
        int userCounter = clientNames.size();
        if (userCounter == 0) {
            userCounterLabel.setText("No users connected.");
            userListLabel.setVisible(false);
            statusHBoxSeparator.setVisible(false);
            return;
        } else if (userCounter == 1) {
            userCounterLabel.setText(userCounter + " user connected");
            userListLabel.setVisible(true);
            statusHBoxSeparator.setVisible(true);
            userListLabel.setText("ID: " + clientNames.keySet().iterator().next());
            return;
        }
        StringBuilder usersInfo = new StringBuilder("IDs: ");
        for (int id : clientNames.keySet()) {
            usersInfo.append(id).append(", ");
        }
        userCounterLabel.setText(userCounter + " users connected");
        userListLabel.setText(String.valueOf(usersInfo).substring(0, usersInfo.length() - 2));
    }

    public void updateServerRules(ServerRules rules) {
        server.updateRules(rules);
        messageInput.setTextLimit(resolveMessageCharacterLimit(rules));
        persistSettings();
    }

    public ServerRules getServerRules() {
        return server.getRules();
    }

    public void updateConnectionStatusIcon(boolean connected) {
        connectionStatusIcon.setIconLiteral(connected ? "fltral-chat-24" : "fltral-chat-off-24");
    }

    @FXML
    private void clearMessages() {
        messageVBox.getChildren().clear();
    }

    @FXML
    private void sendMessage() {
        if (messageInput.getText().isEmpty()) {
            return;
        }
        server.sendServerMessageToAll(messageInput.getText());
        appendMessage("Server: " + messageInput.getText());
        messageInput.clear();
    }

    @FXML
    private void toggleStart() {
        if (server.isRunning()) {
            server.stop();
        } else {
            if (!checkPort()) {
                return;
            }
            server.start(Integer.parseInt(portField.getText()), accessKeyField.getText());
        }
    }

    @FXML
    private void shutdown() {
        persistSettings();
        server.shutdown();
    }

    @FXML
    private void changeSettings() {
        SettingsController.open(this);
    }

    private void persistSettings() {
        try {
            ServerSettingsStore.save(server.getRules(), Integer.parseInt(portField.getText()));
        } catch (NumberFormatException e) {
            Logger.warn(ServerLogMessages.SKIPPING_SETTINGS_SAVE_BECAUSE_PORT_IS_INVALID);
        }
    }

    private String getExternalIp() {
        String ip = "";
        try {
            URL url = new URL("https://api.ipify.org");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            ip = in.readLine();
            in.close();
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_GETTING_EXTERNAL_IP_PREFIX, e), e);
        }
        return ip;
    }

    private String getInternalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        String host = addr.getHostAddress();
                        // Skip 10.x (Docker/VM) if possible, otherwise return first LAN IP.
                        if (!host.startsWith("10.")) {
                            return host;
                        }
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            Logger.error(ServerLogMessages.withCause(ServerLogMessages.ERROR_GETTING_INTERNAL_IP_PREFIX, e), e);
            return "";
        }
    }

    private boolean checkPort() {
        String validationMessage = validatePort(portField.getText());
        if (validationMessage.isEmpty()) {
            return true;
        }
        Logger.warn(validationMessage);
        AttentionController.open(validationMessage);
        return false;
    }

    static String validatePort(String portText) {
        try {
            if (portText.isEmpty()) {
                return ServerAttentionMessages.PORT_MUST_NOT_BE_EMPTY;
            }
            int port = Integer.parseInt(portText);
            if (port < 0 || port > 65535) {
                return ServerAttentionMessages.PORT_MUST_BE_BETWEEN_0_AND_65535;
            }
            return "";
        } catch (NumberFormatException e) {
            return ServerAttentionMessages.PORT_MUST_BE_A_NUMBER;
        }
    }

    private void scrollDown() {
        PauseTransition pause = new PauseTransition(Duration.millis(50));
        pause.setOnFinished(event -> {
            double startValue = scrollPane.getVvalue();
            double endValue = 1.0;
            Timeline timeline = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(scrollPane.vvalueProperty(), startValue)),
                    new KeyFrame(Duration.millis(50), new KeyValue(scrollPane.vvalueProperty(), endValue))
            );
            timeline.play();
        });
        pause.play();
    }

    private int resolveMessageCharacterLimit(ServerRules rules) {
        int messageCharacterLimit = rules.messageCharacterLimit();
        return messageCharacterLimit > 0 ? messageCharacterLimit : Integer.MAX_VALUE;
    }
}
