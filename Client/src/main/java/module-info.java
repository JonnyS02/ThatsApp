module thatsapp.client {
    requires MaterialFX;
    requires javafx.controls;
    requires javafx.fxml;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.ikonli.fluentui;
    requires java.net.http;
    requires thatsapp.common;

    exports thatsapp.client;
    exports thatsapp.client.communication;
    exports thatsapp.client.ui.controllers;
    opens thatsapp.client.ui.controllers to javafx.fxml;
    exports thatsapp.client.data;
    exports thatsapp.client.communication.transport;
    exports thatsapp.client.communication.transport.desktop;
    exports thatsapp.client.communication.transport.web;
    exports thatsapp.client.communication.filetransfer.api;
    exports thatsapp.client.communication.filetransfer.common;
    exports thatsapp.client.communication.filetransfer.desktop;
    exports thatsapp.client.communication.filetransfer.web;
    exports thatsapp.client.ui;
}
