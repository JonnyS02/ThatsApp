package thatsapp.client.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.function.BiConsumer;

public final class FxDialogs {

    private FxDialogs() {
    }

    public static <T> void showModal(Class<?> resourceBase, String fxmlPath, String title, Image icon, boolean resizable,
                                     BiConsumer<Scene, T> onLoaded) throws IOException {
        LoadedView<T> loaded = load(resourceBase, fxmlPath);
        if (onLoaded != null) {
            onLoaded.accept(loaded.scene(), loaded.controller());
        }
        Stage stage = buildStage(loaded.scene(), title, icon, resizable);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.showAndWait();
    }

    public static <T> void showWindow(Class<?> resourceBase, String fxmlPath, String title, Image icon, boolean resizable,
                                      BiConsumer<Stage, T> stageCustomizer, BiConsumer<Scene, T> onLoaded) throws IOException {
        LoadedView<T> loaded = load(resourceBase, fxmlPath);
        if (onLoaded != null) {
            onLoaded.accept(loaded.scene(), loaded.controller());
        }
        Stage stage = buildStage(loaded.scene(), title, icon, resizable);
        if (stageCustomizer != null) {
            stageCustomizer.accept(stage, loaded.controller());
        }
        stage.show();
    }

    public static <T> void showOnStage(Stage stage, Class<?> resourceBase, String fxmlPath,
                                       BiConsumer<Stage, T> stageCustomizer, BiConsumer<Scene, T> onLoaded) throws IOException {
        LoadedView<T> loaded = load(resourceBase, fxmlPath);
        if (onLoaded != null) {
            onLoaded.accept(loaded.scene(), loaded.controller());
        }
        stage.setScene(loaded.scene());
        if (stageCustomizer != null) {
            stageCustomizer.accept(stage, loaded.controller());
        }
        stage.show();
    }

    private static <T> LoadedView<T> load(Class<?> resourceBase, String fxmlPath) throws IOException {
        FXMLLoader loader = new FXMLLoader(resourceBase.getResource(fxmlPath));
        Parent root = loader.load();
        T controller = loader.getController();
        return new LoadedView<>(controller, new Scene(root));
    }

    private static Stage buildStage(Scene scene, String title, Image icon, boolean resizable) {
        Stage stage = new Stage();
        stage.setTitle(title);
        stage.setScene(scene);
        if (icon != null) {
            stage.getIcons().add(icon);
        }
        stage.setResizable(resizable);
        return stage;
    }

    private record LoadedView<T>(T controller, Scene scene) {
    }
}
