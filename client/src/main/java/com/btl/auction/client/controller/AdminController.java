package com.btl.auction.client.controller;

import com.btl.auction.client.view.AppController;
import com.btl.auction.client.view.Screen;
import com.btl.auction.client.view.Ui;
import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.protocol.RequestType;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;

public final class AdminController implements Screen {
    @FXML private ListView<AuctionDto> auctions;
    @FXML private ListView<ItemDto> items;
    @FXML private Label feedback;
    private AppController app;

    @Override
    public void setApp(AppController app) {
        this.app = app;
        auctions.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(AuctionDto auction, boolean empty) {
                super.updateItem(auction, empty);
                setText(empty || auction == null ? "" : "Phiên #" + auction.id()
                        + " · Sản phẩm #" + auction.itemId() + " · " + auction.status()
                        + " · " + Ui.money(auction.currentPriceVnd())
                        + " · Kết thúc " + Ui.date(auction.endAtEpochMs()));
            }
        });
        items.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ItemDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : "#" + item.id() + " " + Ui.itemSummary(item)
                        + " · Seller #" + item.sellerId());
            }
        });
        refresh();
    }

    @FXML
    private void refresh() {
        Ui.receive(app.request(RequestType.GET_AUCTIONS, new JsonObject()), feedback, payload -> {
            auctions.getItems().clear();
            for (JsonElement value : payload.getAsJsonArray("auctions")) {
                auctions.getItems().add(new Gson().fromJson(value, AuctionDto.class));
            }
        });
        Ui.receive(app.request(RequestType.LIST_ITEMS, new JsonObject()), feedback, payload -> {
            items.getItems().clear();
            for (JsonElement value : payload.getAsJsonArray("items")) {
                items.getItems().add(new Gson().fromJson(value, ItemDto.class));
            }
        });
    }

    @FXML
    private void detail() {
        AuctionDto chosen = auctions.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            feedback.setText("Chọn phiên để xem chi tiết.");
        } else {
            app.detail(chosen.id());
        }
    }

    @FXML
    private void markPaid() {
        manage(RequestType.MARK_PAID);
    }

    @FXML
    private void cancel() {
        manage(RequestType.CANCEL_AUCTION);
    }

    private void manage(RequestType type) {
        AuctionDto chosen = auctions.getSelectionModel().getSelectedItem();
        if (chosen == null) {
            feedback.setText("Chọn phiên đã kết thúc.");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("auctionId", chosen.id());
        Ui.receive(app.request(type, payload), feedback, ignored -> {
            feedback.setText("Đã cập nhật trạng thái phiên.");
            refresh();
        });
    }

    @FXML
    private void back() {
        app.show("auctions");
    }
}
