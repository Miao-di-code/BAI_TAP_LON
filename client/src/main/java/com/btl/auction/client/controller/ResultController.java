package com.btl.auction.client.controller;

import com.btl.auction.client.view.AppController;
import com.btl.auction.client.view.Screen;
import com.btl.auction.client.view.Ui;
import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.protocol.RequestType;
import com.google.gson.JsonObject;
import javafx.fxml.FXML;
import javafx.scene.control.Label;

public final class ResultController implements Screen {
    @FXML private Label itemName;
    @FXML private Label status;
    @FXML private Label finalPrice;
    @FXML private Label winner;
    @FXML private Label payment;
    @FXML private Label feedback;
    private AppController app;
    private long auctionId;

    @Override
    public void setApp(AppController app) {
        this.app = app;
    }

    public void open(long id) {
        auctionId = id;
        refresh();
    }

    @FXML
    private void refresh() {
        JsonObject request = new JsonObject();
        request.addProperty("auctionId", auctionId);
        Ui.receive(app.request(RequestType.GET_AUCTION_DETAIL, request), feedback, response -> {
            AuctionDto auction = Ui.parse(response, "auction", AuctionDto.class);
            status.setText(auction.status().name());
            finalPrice.setText(Ui.money(auction.currentPriceVnd()));
            winner.setText(auction.leaderUserId() == null
                    ? "Không có người thắng" : "Bidder #" + auction.leaderUserId());
            payment.setText(switch (auction.status()) {
                case FINISHED -> "Phiên đã kết thúc. Chưa xác nhận thanh toán.";
                case PAID -> "Đã đánh dấu thanh toán.";
                case CANCELED -> "Phiên đã hủy.";
                case OPEN, RUNNING -> "Phiên chưa kết thúc.";
            });
            JsonObject itemRequest = new JsonObject();
            itemRequest.addProperty("itemId", auction.itemId());
            Ui.receive(app.request(RequestType.GET_ITEM, itemRequest), feedback,
                    itemResponse -> itemName.setText(Ui.parse(itemResponse, "item", ItemDto.class).name()));
        });
    }

    @FXML
    private void back() {
        app.detail(auctionId);
    }
}
