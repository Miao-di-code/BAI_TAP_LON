package com.btl.auction.client.view;

import com.btl.auction.client.network.SocketClient;
import com.btl.auction.common.dto.UserDto;
import com.btl.auction.common.protocol.RequestType;
import com.btl.auction.common.protocol.ResponseMessage;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public final class AppController implements AutoCloseable {
    private final Stage stage;
    private final String host;
    private final int port;
    private SocketClient socket;
    private CompletableFuture<Void> connecting;
    private Screen screen;
    private String token;
    private UserDto user;
    private boolean closing;

    public AppController(Stage stage, String host, int port) {
        this.stage = stage;
        this.host = host;
        this.port = port;
    }

    public synchronized CompletableFuture<Void> connect() {
        if (socket != null && socket.isConnected()) {
            return CompletableFuture.completedFuture(null);
        }
        if (connecting != null && !connecting.isDone()) {
            return connecting;
        }
        if (socket != null) {
            socket.close();
        }
        SocketClient next = new SocketClient();
        socket = next;
        next.addDisconnectListener(error -> Platform.runLater(() -> {
            if (!closing && socket == next) {
                token = null;
                user = null;
                show("login");
                alert("Mất kết nối", "Không còn kết nối với server. Hãy đăng nhập lại.");
            }
        }));
        connecting = next.connectAsync(host, port);
        return connecting;
    }

    public CompletableFuture<ResponseMessage> request(RequestType type, JsonObject payload) {
        SocketClient current = socket;
        if (current == null) {
            return CompletableFuture.failedFuture(new IOException("Chưa kết nối server"));
        }
        return current.request(type, token, payload);
    }

    public CompletableFuture<ResponseMessage> authRequest(RequestType type, JsonObject payload) {
        return connect().thenCompose(ignored -> request(type, payload));
    }

    public void signedIn(String sessionToken, UserDto signedInUser) {
        token = sessionToken;
        user = signedInUser;
        show("auctions");
    }

    public void logout() {
        String oldToken = token;
        if (oldToken != null && socket != null && socket.isConnected()) {
            socket.request(RequestType.LOGOUT, oldToken, new JsonObject());
        }
        token = null;
        user = null;
        show("login");
    }

    public void show(String name) {
        try {
            if (screen != null) {
                screen.leave();
            }
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/" + name + ".fxml"));
            Parent root = loader.load();
            Screen next = loader.getController();
            screen = next;
            Scene scene = new Scene(root, 980, 680);
            scene.getStylesheets().add(getClass().getResource("/view/app.css").toExternalForm());
            stage.setScene(scene);
            stage.setTitle("Online Auction System - " + name);
            next.setApp(this);
        } catch (IOException ex) {
            throw new IllegalStateException("Không tải được màn hình " + name, ex);
        }
    }

    public void detail(long id) {
        show("detail");
        ((com.btl.auction.client.controller.DetailController) screen).open(id);
    }

    public void result(long id) {
        show("result");
        ((com.btl.auction.client.controller.ResultController) screen).open(id);
    }

    public UserDto user() {
        return user;
    }

    public SocketClient socket() {
        return socket;
    }

    public static void alert(String title, String message) {
        new Alert(Alert.AlertType.INFORMATION, message).showAndWait();
    }

    @Override
    public void close() {
        closing = true;
        if (screen != null) {
            screen.leave();
        }
        if (socket != null) {
            socket.close();
        }
    }
}
