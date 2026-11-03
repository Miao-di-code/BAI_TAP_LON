package com.btl.auction.client;

import com.btl.auction.client.view.AppController;
import javafx.application.Application;
import javafx.stage.Stage;

public final class ClientMain extends Application {
    private AppController app;

    @Override
    public void start(Stage stage) {
        String host = getParameters().getNamed().getOrDefault("host", "127.0.0.1");
        int port = Integer.parseInt(getParameters().getNamed().getOrDefault("port", "5555"));
        app = new AppController(stage, host, port);
        app.show("login");
        stage.setOnCloseRequest(event -> app.close());
        stage.show();
    }

    @Override
    public void stop() {
        if (app != null) {
            app.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
