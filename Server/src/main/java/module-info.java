module thatsapp.server {
    requires MaterialFX;
    requires javafx.controls;
    requires javafx.fxml;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fluentui;
    requires thatsapp.common;

    exports thatsapp.server;
    exports thatsapp.server.ui.controllers;
    opens thatsapp.server.ui.controllers to javafx.fxml;
}
