package com.btl.auction.client.controller;

import com.btl.auction.client.view.AppController;
import com.btl.auction.client.view.Screen;
import com.btl.auction.client.view.Ui;
import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.common.protocol.RequestType;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

public final class AuctionsController implements Screen {
    @FXML private Label welcome;
    @FXML private Label feedback;
    @FXML private ListView<Row> auctions;
    @FXML private Button sellerButton;
    @FXML private Button adminButton;
    private AppController app;

    @Override
    public void setApp(AppController app) {
        this.app = app;
        welcome.setText("Xin chào, " + app.user().displayName() + " (" + app.user().role() + ")");
        sellerButton.setVisible(app.user().role() == UserRole.SELLER);
        sellerButton.setManaged(sellerButton.isVisible());
        adminButton.setVisible(app.user().role() == UserRole.ADMIN);
        adminButton.setManaged(adminButton.isVisible());
        refresh();
    }

    @FXML
    private void refresh() {
        feedback.setText("Đang tải phiên đấu giá...");
        Ui.receive(app.request(RequestType.GET_AUCTIONS, new JsonObject()), feedback, payload -> {
            List<CompletableFuture<Row>> calls = new ArrayList<>();
            for (JsonElement element : payload.getAsJsonArray("auctions")) {
                AuctionDto auction = new com.google.gson.Gson().fromJson(element, AuctionDto.class);
                JsonObject itemRequest = new JsonObject();
                itemRequest.addProperty("itemId", auction.itemId());
                calls.add(app.request(RequestType.GET_ITEM, itemRequest).thenApply(reply -> {
                    ItemDto item = reply.success() ? Ui.parse(reply.payload(), "item", ItemDto.class) : null;
                    return new Row(auction, item);
                }).exceptionally(error -> new Row(auction, null)));
            }
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).whenComplete(
                    (ignored, error) -> Platform.runLater(() -> {
                        auctions.getItems().setAll(calls.stream().map(CompletableFuture::join).toList());
                        feedback.setText(auctions.getItems().isEmpty() ? "Chưa có phiên đấu giá." : "");
                    }));
        });
    }

    @FXML
    private void detail() {
        Row selected = auctions.getSelectionModel().getSelectedItem();
        if (selected == null) {
            feedback.setText("Chọn một phiên để xem chi tiết.");
        } else {
            app.detail(selected.auction().id());
        }
    }

    @FXML
    private void seller() {
        app.show("seller");
    }

    @FXML
    private void admin() {
        app.show("admin");
    }

    @FXML
    private void logout() {
        app.logout();
    }

    public record Row(AuctionDto auction, ItemDto item) {
        @Override
        public String toString() {
            return Ui.auctionSummary(auction, item) + " · Kết thúc: " + Ui.date(auction.endAtEpochMs());
        }
    }
}
