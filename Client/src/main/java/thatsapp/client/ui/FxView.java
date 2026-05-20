package thatsapp.client.ui;

import io.github.palexdev.materialfx.controls.MFXProgressBar;
import javafx.application.Platform;
import javafx.scene.control.Label;
import org.kordamp.ikonli.javafx.FontIcon;
import thatsapp.client.communication.filetransfer.api.FileTransferView;

import java.util.concurrent.atomic.AtomicBoolean;

final class FxView implements FileTransferView {
    private final Label progressLabel;
    private final MFXProgressBar progressBar;
    private final FontIcon cancelIcon;
    private final FontIcon actionIcon;
    private final AtomicBoolean terminalState = new AtomicBoolean(false);

    FxView(Label progressLabel, MFXProgressBar progressBar, FontIcon cancelIcon, FontIcon actionIcon) {
        this.progressLabel = progressLabel;
        this.progressBar = progressBar;
        this.cancelIcon = cancelIcon;
        this.actionIcon = actionIcon;
    }

    @Override
    public void showDownloadPrompt(Runnable action) {
        if (actionIcon != null && action != null) {
            Platform.runLater(() -> {
                actionIcon.setIconLiteral("fltfal-arrow-download-24");
                actionIcon.setOnMouseClicked(e -> action.run());
            });
        }
    }

    @Override
    public void setCancelAction(Runnable action) {
        if (actionIcon != null && action != null) {
            Platform.runLater(() -> {
                actionIcon.setIconLiteral("fltfal-dismiss-circle-24");
                actionIcon.setOnMouseClicked(e -> action.run());
            });
        } else if (cancelIcon != null && action != null) {
            Platform.runLater(() -> cancelIcon.setOnMouseClicked(e -> action.run()));
        }
    }

    @Override
    public void updateProgress(double progress, String label) {
        if (terminalState.get()) {
            return;
        }
        Platform.runLater(() -> {
            if (terminalState.get()) {
                return;
            }
            progressBar.setProgress(progress);
            progressLabel.setText(label);
        });
    }

    @Override
    public void markFinished() {
        if (!terminalState.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            progressBar.setProgress(1.0);
            progressLabel.setText("Done");
            if (actionIcon != null) {
                setIconState(actionIcon, "fltfal-checkmark-circle-24", false);
                actionIcon.setOnMouseClicked(null);
            }
            if (cancelIcon != null) {
                setIconState(cancelIcon, "fltfal-checkmark-circle-24", false);
                cancelIcon.setOnMouseClicked(null);
            }
        });
    }

    @Override
    public void markCanceled(String reason) {
        if (!terminalState.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(() -> {
            progressLabel.setText(reason);
            progressBar.setProgress(0);
            if (actionIcon != null) {
                setIconState(actionIcon, "fltfal-dismiss-circle-24", true);
                actionIcon.setOnMouseClicked(null);
            }
            if (cancelIcon != null) {
                setIconState(cancelIcon, "fltfal-dismiss-circle-24", true);
                cancelIcon.setOnMouseClicked(null);
            }
        });
    }

    private static void setIconState(FontIcon icon, String literal, boolean danger) {
        icon.setIconLiteral(literal);
        if (danger) {
            icon.getStyleClass().remove("icon-success");
            icon.getStyleClass().add("icon-danger");
        } else {
            icon.getStyleClass().remove("icon-danger");
            icon.getStyleClass().add("icon-success");
        }
    }
}
