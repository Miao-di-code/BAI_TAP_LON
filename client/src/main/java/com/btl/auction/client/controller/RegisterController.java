package com.btl.auction.client.controller;

import com.btl.auction.client.view.AppController;
import com.btl.auction.client.view.Screen;
import com.btl.auction.client.view.Ui;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.common.protocol.RequestType;
import com.google.gson.JsonObject;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;

public final class RegisterController implements Screen {
    @FXML private TextField username;
    @FXML private TextField displayName;
    @FXML private PasswordField password;
    @FXML private PasswordField confirm;
    @FXML private ChoiceBox<UserRole> role;
    @FXML private Button registerButton;
    @FXML private Label feedback;
    private AppController app;

    @Override
    public void setApp(AppController app) {
        this.app = app;
        role.getItems().setAll(UserRole.BIDDER, UserRole.SELLER);
        role.setValue(UserRole.BIDDER);
    }

    @FXML
    private void register() {
        if (username.getText().isBlank() || displayName.getText().isBlank()
                || password.getText().isBlank()) {
            feedback.setText("Điền đầy đủ thông tin.");
            return;
        }
        if (!password.getText().equals(confirm.getText())) {
            feedback.setText("Mật khẩu xác nhận không khớp.");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username.getText().trim());
        payload.addProperty("displayName", displayName.getText().trim());
        payload.addProperty("password", password.getText());
        payload.addProperty("role", role.getValue().name());
        registerButton.setDisable(true);
        var call = app.authRequest(RequestType.REGISTER, payload);
        call.whenComplete((reply, error) -> javafx.application.Platform.runLater(
                () -> registerButton.setDisable(false)));
        Ui.receive(call, feedback, reply -> {
            password.clear();
            confirm.clear();
            feedback.setText("Đăng ký thành công. Quay lại đăng nhập.");
        });
    }

    @FXML
    private void back() {
        app.show("login");
    }
}
