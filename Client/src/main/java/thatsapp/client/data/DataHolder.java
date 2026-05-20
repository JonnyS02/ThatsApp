package thatsapp.client.data;

import javafx.scene.image.Image;
import thatsapp.client.Main;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class DataHolder {
    public static Image icon = new Image(Objects.requireNonNull(Main.class.getResourceAsStream("/img/icon.png")));
    public static String title = "ThatsApp";
    public static String[] connectionTypes = {"Desktopserver", "Webserver"};
    public static boolean fileExists = false;

    public static String attentionMessage = "";
    public static boolean attentionConfirmed = false;
    public static boolean isConnected = false;
    public static boolean isConnecting = false;
    public static String password = "";
    public static int closingStatus = 0;
    public static String connectionErrorMessage = "";

    // Saved data
    public static String hostIp = "localhost";
    public static int port = 8000;
    public static String desktopserverAccessKey = "";
    public static String desktopserverRoomSecret = "";

    public static String url = "https://jonathan-stengl.de/ThatsAppApi/";
    public static String webserverAccessKey = "";
    public static String webserverRoomSecret = "";
    public static String sessionName = "default";
    public static boolean autoCreate = true;

    public static String name = "Jamie";
    public static String connectionType = "Webserver";
    public static boolean autoConnect = false;

    public static String fileDownloadPath = defaultDownloadPath();
    public static boolean autoDownloadFiles = false;
    public static String messagePaneBackgroundImageSource = "";
    public static String messagePaneBackgroundImageData = "";
    public static String messagePaneBackgroundImageMimeType = "";

    private DataHolder() {
    }

    private static String defaultDownloadPath() {
        Path home = Path.of(System.getProperty("user.home"));
        Path desktop = home.resolve("Desktop");
        return Files.isDirectory(desktop) ? desktop.toString() : home.toString();
    }
}
