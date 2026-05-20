package thatsapp.client.ui.controllers;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXComboBox;
import io.github.palexdev.materialfx.controls.MFXProgressSpinner;
import io.github.palexdev.materialfx.controls.MFXScrollPane;
import io.github.palexdev.materialfx.controls.MFXTextField;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import thatsapp.common.ChatMessagePayload;
import thatsapp.common.Logger;
import thatsapp.client.Main;
import thatsapp.client.communication.CommunicationFacade;
import thatsapp.client.data.DataEncryptor;
import thatsapp.client.data.DataHolder;
import thatsapp.client.messages.ClientAttentionMessages;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.client.ui.FxDialogs;

import java.io.File;
import java.util.List;
import java.util.Locale;
import javafx.stage.Stage;

public class MainController {

    private static final long TYPING_THROTTLE_MS = 1000;
    private static final long TYPING_TTL_MS = 1500;
    private static final int TYPING_DELTA_THRESHOLD = 3;
    private static final String[] GROUP_USERNAME_COLORS = {
            "#0B3D91",
            "#1E4D2B",
            "#004D40",
            "#4A148C",
            "#311B92",
            "#880E4F",
            "#7F0000",
            "#5D4037",
            "#263238",
            "#0D47A1",
    };

    @FXML
    private MFXScrollPane scrollPane;

    @FXML
    private MFXToggleButton autoConnectSwitch;

    @FXML
    private VBox messageVBox;

    @FXML
    private MFXTextField nameField;

    @FXML
    private MFXComboBox<String> connectionBox;

    @FXML
    private MFXButton configButton;

    @FXML
    private MFXButton connectButton;

    @FXML
    private MFXTextField messageInput;

    @FXML
    private MFXButton sendTextButton;

    @FXML
    private MFXButton sendFileButton;

    @FXML
    private Label headlineLabel;

    @FXML
    private FontIcon connectionStatusIcon;

    private final MFXProgressSpinner spinner = new MFXProgressSpinner();

    private PauseTransition typingPause;
    private long lastTypingSent = 0;
    private int lastTypingLength = 0;
    private int messageCharacterLimit = Integer.MAX_VALUE;
    private boolean inputEnabled;
    private boolean fileTransferEnabled;

    private final CommunicationFacade connectionHelper = new CommunicationFacade(this);

    public static void open() {
        try {
            FxDialogs.showWindow(
                    Main.class,
                    "main.fxml",
                    DataHolder.title,
                    DataHolder.icon,
                    true,
                    (Stage stage, MainController controller) -> {
                        stage.setOnCloseRequest(e -> controller.exit());
                        stage.setMinHeight(470);
                        stage.setMinWidth(440);
                    },
                    (Scene scene, MainController controller) -> scene.setOnKeyPressed(e -> {
                        if (e.getCode().getName().equals("Enter")) {
                            controller.sendText();
                        }
                    })
            );
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_OPENING_MAIN_WINDOW_PREFIX, e), e);
        }
    }

    @FXML
    private void initialize() {
        nameField.setText(DataHolder.name);
        connectionBox.getItems().addAll(DataHolder.connectionTypes);
        if (DataHolder.connectionType.equals(DataHolder.connectionTypes[0])) {
            connectionBox.selectIndex(0);
        } else {
            connectionBox.selectIndex(1);
        }
        autoConnectSwitch.setSelected(DataHolder.autoConnect);
        if (DataHolder.autoConnect) {
            connectionHelper.connecting(connectionBox.getSelectedItem());
        }
        messageVBox.minWidthProperty().bind(scrollPane.widthProperty().subtract(5));
        messageVBox.maxWidthProperty().bind(scrollPane.widthProperty().subtract(5));
        spinner.prefWidthProperty().bind(scrollPane.widthProperty());
        toggleStyleClass(spinner.getStyleClass(), "mfx-progress-spinner-shadow", true);
        scrollPane.setOnDragOver(this::onDragOver);
        scrollPane.setOnDragDropped(this::onDragDropped);
        ChangeBackgroundController.applyStoredBackground(this);
        updateConnectionStatusIcon(DataHolder.isConnected);
        setupTypingHandlers();
        messageInput.textProperty().addListener((obs, oldValue, newValue) -> enforceMessageLimit(newValue));
    }

    public void appendMessage(String messageText, String fxml, String username) {
        String timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        try {
            FXMLLoader loader = new FXMLLoader(Main.class.getResource("messages/" + fxml));
            HBox messageBox = loader.load();
            Label messageLabel = (Label) messageBox.lookup("#messageLabel");
            if (!fxml.equals("system.fxml")) {
                Label timestampLabel = (Label) messageBox.lookup("#timestamp");
                bindMessageWidth(messageLabel, timestampLabel);
                timestampLabel.setText(timestamp);
            } else {
                bindMessageWidth(messageLabel, null);
            }
            if (fxml.equals("otherText.fxml")) {
                Label userLabel = (Label) messageBox.lookup("#userLabel");
                userLabel.setText(username);
                applyShuffledUserNameColor(userLabel, username);
                VBox messageVBox = (VBox) messageBox.lookup("#messageVBox");
                boolean isConnectionMessage = messageText.equalsIgnoreCase("Joined the chat") || messageText.equalsIgnoreCase("Left the chat");
                if (username.equals("Server")) {
                    messageVBox.getStyleClass().remove("other-message");
                    messageVBox.getStyleClass().add("server-message");
                } else if (isConnectionMessage) {
                    messageVBox.getStyleClass().remove("other-message");
                    messageVBox.getStyleClass().add("connection-message");
                }
            }
            messageLabel.setText(messageText);
            addMessageNode(messageBox);
            scrollPane.layout();
            scrollDown();
            if (fxml.equals("ownText.fxml")) {
                messageInput.clear();
                lastTypingLength = 0;
            }
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_LOADING_MESSAGE_LAYOUT_PREFIX, e), e);
        }
    }

    public void bindMessageWidth(Label messageLabel, Label timestampLabel) {
        if (timestampLabel != null) {
            messageLabel.maxWidthProperty().bind(scrollPane.widthProperty().multiply(0.8).subtract(timestampLabel.widthProperty()));
        } else {
            messageLabel.maxWidthProperty().bind(scrollPane.widthProperty().multiply(0.8));
        }
    }

    public void applyShuffledUserNameColor(Label userLabel, String username) {
        if (username.equalsIgnoreCase("Server")) {
            userLabel.setStyle("");
            return;
        }
        int index = resolveGroupUserColorIndex(username);
        userLabel.setStyle("-fx-text-fill: " + GROUP_USERNAME_COLORS[index] + ";");
    }

    public void scrollDown() {
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

    public void updateConnectionStatusIcon(boolean connected) {
        connectionStatusIcon.setIconLiteral(connected ? "fltral-chat-24" : "fltral-chat-off-24");
    }

    public void applyBackgroundStyle(String style, boolean enable) {
        scrollPane.setStyle(enable ? style : "");
        toggleBackgroundImageMode(enable);
    }

    public void addMessageNode(Node node) {
        messageVBox.getChildren().add(node);
    }

    public void clearMessageNodes() {
        messageVBox.getChildren().clear();
    }

    public void showSpinner() {
        if (!messageVBox.getChildren().contains(spinner)) {
            messageVBox.getChildren().add(spinner);
        }
    }

    public void hideSpinner() {
        messageVBox.getChildren().remove(spinner);
    }

    public void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        messageInput.setDisable(!enabled);
        sendTextButton.setDisable(!enabled);
        updateFileTransferControls();
    }

    public void setConnectionControlsEnabled(boolean enabled) {
        nameField.setEditable(enabled);
        connectionBox.setDisable(!enabled);
        configButton.setDisable(!enabled);
    }

    public void setConnectButtonState(boolean danger, String text) {
        connectButton.setDisable(false);
        toggleStyleClass(connectButton.getStyleClass(), "button-success", !danger);
        toggleStyleClass(connectButton.getStyleClass(), "button-danger", danger);
        connectButton.setText(text);
    }

    public void setConnectButtonDisabled(boolean disabled) {
        connectButton.setDisable(disabled);
    }

    public void setHeadlineText(String text) {
        headlineLabel.setText(text);
    }

    public void setMessageCharacterLimit(int limit) {
        messageCharacterLimit = limit > 0 ? limit : Integer.MAX_VALUE;
        enforceMessageLimit(messageInput.getText());
    }

    public void setFileTransferEnabled(boolean enabled) {
        fileTransferEnabled = enabled;
        updateFileTransferControls();
    }

    @FXML
    private void changeName() {
        DataHolder.name = nameField.getText();
        DataEncryptor.saveEncrypted();
    }

    @FXML
    private void changeConnectionType() {
        DataHolder.connectionType = connectionBox.getSelectedItem();
        DataEncryptor.saveEncrypted();
    }

    @FXML
    private void openConfig() {
        ConfigController.open();
    }

    @FXML
    private void openFileHandling() {
        FileHandlingController.open();
    }

    @FXML
    private void onDragOver(DragEvent event) {
        if (!canUseFileTransfer()) {
            return;
        }
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    @FXML
    private void onDragDropped(DragEvent event) {
        if (!canUseFileTransfer()) {
            return;
        }
        Dragboard db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles()) {
            connectionHelper.sendFiles(db.getFiles());
            success = true;
        }
        event.setDropCompleted(success);
        event.consume();
    }

    @FXML
    private void toggleAutoConnect() {
        DataHolder.autoConnect = autoConnectSwitch.isSelected();
        DataEncryptor.saveEncrypted();
    }

    @FXML
    private void clearMessages() {
        messageVBox.getChildren().removeIf(node -> node != spinner);
    }

    @FXML
    private void sendText() {
        if (messageInput.getText().isEmpty() || !DataHolder.isConnected) {
            return;
        }
        connectionHelper.sendTypingStopped();
        String messageText = messageInput.getText();
        appendMessage(messageText, "ownText.fxml", "");
        connectionHelper.sendMessage(messageText);
    }

    @FXML
    private void changePassword() {
        ChangePasswordController.open();
    }

    @FXML
    private void openBackgroundDialog() {
        ChangeBackgroundController.open(this);
    }

    @FXML
    private void toggleConnect() {
        if (nameField.getText().isEmpty()) {
            AttentionController.open(false, ClientAttentionMessages.PLEASE_ENTER_NAME);
            return;
        }
        if (DataHolder.isConnecting) {
            connectionHelper.cancelConnecting();
            connectButton.setDisable(true);
            connectButton.setText("Canceling...");
            return;
        }
        if (!DataHolder.isConnected) {
            connectionHelper.connecting(connectionBox.getSelectedItem());
        } else {
            DataHolder.closingStatus = 1;
            connectionHelper.disconnecting();
        }
    }

    @FXML
    private void sendFile() {
        if (!canUseFileTransfer()) {
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Files to Send");
        Stage stage = (Stage) messageVBox.getScene().getWindow();
        List<File> selectedFiles = fileChooser.showOpenMultipleDialog(stage);
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            return;
        }
        connectionHelper.sendFiles(selectedFiles);
    }

    @FXML
    private void onMessageTyping() {
        handleTypingSignal();
    }

    private void exit() {
        if (DataHolder.isConnected) {
            connectionHelper.disconnecting();
        }
        System.exit(0);
    }

    private void toggleBackgroundImageMode(boolean enable) {
        toggleStyleClass(scrollPane.getStyleClass(), "message-pane-background-image", enable);
    }

    private void setupTypingHandlers() {
        typingPause = new PauseTransition(Duration.millis(TYPING_TTL_MS));
        typingPause.setOnFinished(event -> connectionHelper.sendTypingStopped());
    }

    private void handleTypingSignal() {
        if (!DataHolder.isConnected) {
            return;
        }
        int currentLength = getNonSpaceLength(messageInput.getText());
        if (Math.abs(currentLength - lastTypingLength) < TYPING_DELTA_THRESHOLD) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTypingSent >= TYPING_THROTTLE_MS) {
            lastTypingSent = now;
            connectionHelper.sendTypingPing(TYPING_TTL_MS);
            lastTypingLength = currentLength;
        }
        typingPause.stop();
        typingPause.playFromStart();
    }

    private int getNonSpaceLength(String text) {
        if (text == null) {
            return 0;
        }
        return ChatMessagePayload.countCharacters(text.replaceAll("\\s", ""));
    }

    private void enforceMessageLimit(String text) {
        if (text == null || ChatMessagePayload.countCharacters(text) <= messageCharacterLimit) {
            return;
        }
        String trimmed = ChatMessagePayload.trimToCharacterLimit(text, messageCharacterLimit);
        if (trimmed.equals(messageInput.getText())) {
            return;
        }
        messageInput.setText(trimmed);
        messageInput.positionCaret(trimmed.length());
    }

    private void toggleStyleClass(List<String> classes, String styleClass, boolean enable) {
        if (enable) {
            if (!classes.contains(styleClass)) {
                classes.add(styleClass);
            }
        } else {
            classes.remove(styleClass);
        }
    }

    private int resolveGroupUserColorIndex(String username) {
        int hash = username.toLowerCase(Locale.ROOT).hashCode();
        hash = hash ^ (hash >>> 16);
        return Math.floorMod(hash, GROUP_USERNAME_COLORS.length);
    }

    private void updateFileTransferControls() {
        sendFileButton.setDisable(!inputEnabled || !fileTransferEnabled);
    }

    private boolean canUseFileTransfer() {
        return DataHolder.isConnected && fileTransferEnabled;
    }
}
