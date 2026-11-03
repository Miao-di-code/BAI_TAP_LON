package com.btl.auction.client.controller;

import com.btl.auction.client.view.AppController;
import com.btl.auction.client.view.Screen;
import com.btl.auction.client.view.Ui;
import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.model.ItemCategory;
import com.btl.auction.common.protocol.RequestType;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import javafx.fxml.FXML;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

public final class SellerController implements Screen {
    @FXML private ListView<ItemDto> items;
    @FXML private ListView<String> ownAuctions;
    @FXML private ChoiceBox<ItemCategory> category;
    @FXML private TextField name;
    @FXML private TextArea description;
    @FXML private TextField price;
    @FXML private TextField detail;
    @FXML private TextField start;
    @FXML private TextField end;
    @FXML private Label feedback;
    private AppController app;

    @Override
    public void setApp(AppController app) {
        this.app = app;
        category.getItems().setAll(ItemCategory.values());
        category.setValue(ItemCategory.ELECTRONICS);
        quickTimes();
        items.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ItemDto item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : "#" + item.id() + " " + Ui.itemSummary(item)
                        + " · " + Ui.money(item.startingPriceVnd()));
            }
        });
        items.getSelectionModel().selectedItemProperty().addListener((ignored, old, selected) -> {
            if (selected != null) {
                quickTimes();
                category.setValue(selected.category());
                category.setDisable(true);
                name.setText(selected.name());
                description.setText(selected.description());
                price.setText(Long.toString(selected.startingPriceVnd()));
                detail.setText(selected.detail());
            } else {
                category.setDisable(false);
            }
        });
        refresh();
    }

    @FXML
    private void refresh() {
        Ui.receive(app.request(RequestType.GET_MY_ITEMS, new JsonObject()), feedback, payload -> {
            items.getItems().clear();
            for (JsonElement element : payload.getAsJsonArray("items")) {
                items.getItems().add(new Gson().fromJson(element, ItemDto.class));
            }
            loadAuctions();
        });
    }

    private void loadAuctions() {
        Ui.receive(app.request(RequestType.GET_AUCTIONS, new JsonObject()), feedback, payload -> {
            Map<Long, ItemDto> own = new HashMap<>();
            items.getItems().forEach(item -> own.put(item.id(), item));
            ownAuctions.getItems().clear();
            for (JsonElement element : payload.getAsJsonArray("auctions")) {
                AuctionDto auction = new Gson().fromJson(element, AuctionDto.class);
                ItemDto item = own.get(auction.itemId());
                if (item != null) {
                    ownAuctions.getItems().add("#" + auction.id() + " "
                            + Ui.auctionSummary(auction, item) + " · " + Ui.date(auction.endAtEpochMs()));
                }
            }
        });
    }

    private JsonObject itemPayload() {
        if (name.getText().isBlank() || description.getText().isBlank()
                || detail.getText().isBlank()) {
            throw new IllegalArgumentException("Điền tên, mô tả và chi tiết sản phẩm.");
        }
        long value;
        try {
            value = Long.parseLong(price.getText().trim());
            if (value < 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Giá khởi điểm phải là số nguyên không âm.");
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("category", category.getValue().name());
        payload.addProperty("name", name.getText().trim());
        payload.addProperty("description", description.getText().trim());
        payload.addProperty("startingPriceVnd", value);
        payload.addProperty("detail", detail.getText().trim());
        return payload;
    }

    @FXML
    private void createItem() {
        try {
            Ui.receive(app.request(RequestType.CREATE_ITEM, itemPayload()), feedback, ignored -> {
                feedback.setText("Đã thêm sản phẩm.");
                refresh();
            });
        } catch (IllegalArgumentException ex) {
            feedback.setText(ex.getMessage());
        }
    }

    @FXML
    private void updateItem() {
        ItemDto item = items.getSelectionModel().getSelectedItem();
        if (item == null) {
            feedback.setText("Chọn sản phẩm cần sửa.");
            return;
        }
        try {
            JsonObject payload = itemPayload();
            payload.addProperty("itemId", item.id());
            Ui.receive(app.request(RequestType.UPDATE_ITEM, payload), feedback, ignored -> {
                feedback.setText("Đã sửa sản phẩm.");
                refresh();
            });
        } catch (IllegalArgumentException ex) {
            feedback.setText(ex.getMessage());
        }
    }

    @FXML
    private void deleteItem() {
        ItemDto item = items.getSelectionModel().getSelectedItem();
        if (item == null) {
            feedback.setText("Chọn sản phẩm cần xóa.");
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("itemId", item.id());
        Ui.receive(app.request(RequestType.DELETE_ITEM, payload), feedback, ignored -> {
            feedback.setText("Đã xóa sản phẩm.");
            refresh();
        });
    }

    @FXML
    private void createAuction() {
        ItemDto item = items.getSelectionModel().getSelectedItem();
        if (item == null) {
            feedback.setText("Chọn sản phẩm để tạo phiên.");
            return;
        }
        try {
            long from = Ui.epoch(start.getText());
            long to = Ui.epoch(end.getText());
            if (from < System.currentTimeMillis() || to <= from) {
                feedback.setText("Bắt đầu phải trong tương lai; kết thúc phải sau bắt đầu.");
                return;
            }
            JsonObject payload = new JsonObject();
            payload.addProperty("itemId", item.id());
            payload.addProperty("startAtEpochMs", from);
            payload.addProperty("endAtEpochMs", to);
            Ui.receive(app.request(RequestType.CREATE_AUCTION, payload), feedback, response -> {
                feedback.setText("Đã tạo phiên đấu giá.");
                loadAuctions();
            });
        } catch (RuntimeException ex) {
            feedback.setText("Nhập thời gian dạng dd/MM/yyyy HH:mm:ss.");
        }
    }

    @FXML
    private void quickTimes() {
        long now = System.currentTimeMillis();
        start.setText(Ui.date(now + 30_000));
        end.setText(Ui.date(now + 150_000));
    }

    @FXML
    private void clear() {
        items.getSelectionModel().clearSelection();
        category.setDisable(false);
        category.setValue(ItemCategory.ELECTRONICS);
        name.clear();
        description.clear();
        price.clear();
        detail.clear();
    }

    @FXML
    private void back() {
        app.show("auctions");
    }
}
