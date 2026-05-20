package thatsapp.client.ui.controllers;

import io.github.palexdev.materialfx.controls.MFXButton;
import io.github.palexdev.materialfx.controls.MFXTextField;
import io.github.palexdev.materialfx.controls.MFXToggleButton;
import javafx.fxml.FXML;
import javafx.stage.DirectoryChooser;
import thatsapp.client.data.DataHolder;
import thatsapp.client.data.DataEncryptor;
import thatsapp.client.messages.ClientLogMessages;
import thatsapp.common.Logger;
import thatsapp.client.Main;
import thatsapp.client.ui.FxDialogs;

import java.io.File;
import javafx.stage.Stage;

public class FileHandlingController {

    @FXML
    private MFXTextField pathField;

    @FXML
    private MFXToggleButton autoDownloadToggle;

    @FXML
    private MFXButton saveButton;

    public static void open() {
        try {
            FxDialogs.showModal(Main.class, "fileHandling.fxml", "File Handling", DataHolder.icon, false, null);
        } catch (Exception e) {
            Logger.error(ClientLogMessages.withCause(ClientLogMessages.ERROR_OPENING_FILE_HANDLING_WINDOW_PREFIX, e), e);
        }
    }

    @FXML
    private void initialize() {
        pathField.setText(DataHolder.fileDownloadPath);
        autoDownloadToggle.setSelected(DataHolder.autoDownloadFiles);
    }

    @FXML
    private void browse() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Download Folder");
        File initial = new File(pathField.getText());
        if (initial.exists() && initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File selected = chooser.showDialog(pathField.getScene().getWindow());
        if (selected != null) {
            pathField.setText(selected.getAbsolutePath());
            Logger.info(ClientLogMessages.downloadFolderChosen(selected.getAbsolutePath()));
        }
    }

    @FXML
    private void save() {
        DataHolder.fileDownloadPath = pathField.getText();
        DataHolder.autoDownloadFiles = autoDownloadToggle.isSelected();
        Logger.info(ClientLogMessages.fileHandlingSaved(DataHolder.fileDownloadPath, DataHolder.autoDownloadFiles));
        DataEncryptor.saveEncrypted();
        close();
    }

    @FXML
    private void close() {
        Stage stage = (Stage) saveButton.getScene().getWindow();
        stage.close();
    }
}
