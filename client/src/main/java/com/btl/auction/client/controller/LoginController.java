package com.btl.auction.client.controller;

import com.btl.auction.client.view.AppController;
import com.btl.auction.client.view.Screen;
import com.btl.auction.client.view.Ui;
import com.btl.auction.common.dto.UserDto;
import com.btl.auction.common.protocol.RequestType;
import com.google.gson.JsonObject;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public final class LoginController implements Screen {
    @FXML private TextField username;
    @FXML private PasswordField password;
    @FXML private Button loginButton;
    @FXML private Label feedback;
    private AppController app;

    @Override
    public void setApp(AppController app) {
        this.app = app;
    }

    @FXML
    private void login() {
        if (username.getText().isBlank() || password.getText().isBlank()) {
            feedback.setText("Nhập tên đăng nhập và mật khẩu.");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username.getText().trim());
        payload.addProperty("password", password.getText());
        loginButton.setDisable(true);
        var call = app.authRequest(RequestType.LOGIN, payload);
        call.whenComplete((reply, error) -> javafx.application.Platform.runLater(
                () -> loginButton.setDisable(false)));
        Ui.receive(call, feedback, reply -> app.signedIn(
                reply.get("token").getAsString(), Ui.parse(reply, "user", UserDto.class)));
    }

    @FXML
    private void register() {
        app.show("register");
    }
}
