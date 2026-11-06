package com.btl.auction.client.controller;

import com.btl.auction.client.view.AppController;
import com.btl.auction.client.view.Screen;
import com.btl.auction.client.view.Ui;
import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.dto.BidDto;
import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.model.AuctionStatus;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.common.protocol.EventMessage;
import com.btl.auction.common.protocol.RequestType;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

public final class DetailController implements Screen {
    @FXML private Label title;
    @FXML private Label description;
    @FXML private Label seller;
    @FXML private Label startingPrice;
    @FXML private Label currentPrice;
    @FXML private Label leader;
    @FXML private Label starts;
    @FXML private Label ends;
    @FXML private Label status;
    @FXML private Label remaining;
    @FXML private Label result;
    @FXML private Label feedback;
    @FXML private TextField amount;
    @FXML private Button bidButton;
    @FXML private Button paidButton;
    @FXML private Button cancelButton;
    @FXML private Button resultButton;
    @FXML private ListView<String> bids;
    private AppController app;
    private long auctionId;
    private AuctionDto auction;
    private ItemDto item;
    private boolean itemRequested;
    private boolean historyRequested;
    private boolean bidPending;
    private Timeline clock;
    private Consumer<EventMessage> eventListener;

    @Override
    public void setApp(AppController app) {
        this.app = app;
    }

    public void open(long id) {
        auctionId = id;
        bidButton.setDisable(true);
        eventListener = event -> {
            if (event.auctionId() == id) {
                Platform.runLater(() -> {
                    AuctionDto latest = app.socket().latestAuction(id);
                    if (latest != null) {
                        showAuction(latest);
                    }
                    if (event.type() == com.btl.auction.common.protocol.EventType.BID_UPDATED) {
                        history();
                    }
                });
            }
        };
        app.socket().addEventListener(eventListener);
        clock = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> updateTime()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        JsonObject input = idPayload();
        Ui.receive(app.request(RequestType.SUBSCRIBE_AUCTION, input), feedback,
                payload -> showAuction(Ui.parse(payload, "auction", AuctionDto.class)));
        Ui.receive(app.request(RequestType.GET_AUCTION_DETAIL, idPayload()), feedback,
                payload -> showAuction(Ui.parse(payload, "auction", AuctionDto.class)));
    }

    private JsonObject idPayload() {
        JsonObject input = new JsonObject();
        input.addProperty("auctionId", auctionId);
        return input;
    }

    private void showItem(ItemDto item) {
        this.item = item;
        title.setText(item.name() + " · " + item.category());
        description.setText(item.description() + "\nChi tiết: " + item.detail());
        seller.setText("Seller #" + item.sellerId());
        startingPrice.setText(Ui.money(item.startingPriceVnd()));
        updateActions();
    }

    private void showAuction(AuctionDto value) {
        AuctionDto latest = app.socket().latestAuction(auctionId);
        auction = latest == null ? value : latest;
        if (!historyRequested) {
            historyRequested = true;
            history();
        }
        currentPrice.setText(Ui.money(auction.currentPriceVnd()));
        leader.setText(auction.leaderUserId() == null ? "Chưa có" : "Bidder #" + auction.leaderUserId());
        starts.setText(Ui.date(auction.startAtEpochMs()));
        ends.setText(Ui.date(auction.endAtEpochMs()));
        status.setText(auction.status().name());
        boolean finished = auction.status() == AuctionStatus.FINISHED;
        result.setVisible(finished || auction.status() == AuctionStatus.PAID
                || auction.status() == AuctionStatus.CANCELED);
        result.setManaged(result.isVisible());
        resultButton.setVisible(result.isVisible());
        resultButton.setManaged(result.isVisible());
        if (result.isVisible()) {
            result.setText("Kết quả: " + auction.status() + " · Giá cuối: "
                    + Ui.money(auction.currentPriceVnd()) + " · Người thắng: "
                    + (auction.leaderUserId() == null ? "Không có" : "Bidder #" + auction.leaderUserId())
                    + (auction.status() == AuctionStatus.PAID ? " · Đã đánh dấu thanh toán" : "")
                    + (auction.status() == AuctionStatus.CANCELED ? " · Đã hủy" : ""));
        }
        updateActions();
        updateTime();
        if (!itemRequested) {
            itemRequested = true;
            JsonObject itemRequest = new JsonObject();
            itemRequest.addProperty("itemId", auction.itemId());
            Ui.receive(app.request(RequestType.GET_ITEM, itemRequest), feedback,
                    payload -> showItem(Ui.parse(payload, "item", ItemDto.class)));
        }
    }

    private void updateActions() {
        if (auction == null || app.user() == null) {
            return;
        }
        bidButton.setDisable(bidPending || app.user().role() != UserRole.BIDDER
                || auction.status() != AuctionStatus.RUNNING);
        boolean canManage = app.user().role() == UserRole.ADMIN
                || (app.user().role() == UserRole.SELLER && item != null
                && item.sellerId() == app.user().id());
        boolean finished = auction.status() == AuctionStatus.FINISHED;
        paidButton.setVisible(canManage && finished && auction.leaderUserId() != null);
        paidButton.setManaged(paidButton.isVisible());
        cancelButton.setVisible(canManage && finished);
        cancelButton.setManaged(cancelButton.isVisible());
    }

    private void updateTime() {
        if (auction == null) {
            return;
        }
        long seconds = Math.max(0, Duration.between(Instant.now(),
                Instant.ofEpochMilli(auction.endAtEpochMs())).getSeconds());
        remaining.setText(auction.status() == AuctionStatus.RUNNING
                ? String.format("%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : auction.status() == AuctionStatus.OPEN ? "Chưa bắt đầu" : "Đã kết thúc");
    }

    @FXML
    private void bid() {
        long value;
        try {
            value = Ui.positiveLong(amount.getText());
        } catch (NumberFormatException ex) {
            feedback.setText("Giá bid phải là số nguyên dương.");
            return;
        }
        JsonObject input = idPayload();
        input.addProperty("amountVnd", value);
        bidPending = true;
        updateActions();
        var call = app.request(RequestType.PLACE_BID, input);
        call.whenComplete((response, error) -> Platform.runLater(() -> {
            bidPending = false;
            updateActions();
        }));
        Ui.receive(call, feedback, payload -> {
            showAuction(Ui.parse(payload, "auction", AuctionDto.class));
            amount.clear();
            feedback.setText("Đặt giá thành công.");
            history();
        });
    }

    @FXML
    private void history() {
        Ui.receive(app.request(RequestType.GET_BID_HISTORY, idPayload()), feedback, payload -> {
            var values = new java.util.ArrayList<BidDto>();
            for (JsonElement element : payload.getAsJsonArray("bids")) {
                values.add(new Gson().fromJson(element, BidDto.class));
            }
            values.sort(Comparator.comparingLong(BidDto::createdAtEpochMs).thenComparingLong(BidDto::id));
            bids.getItems().setAll(values.stream().map(bid -> Ui.date(bid.createdAtEpochMs())
                    + " · Bidder #" + bid.bidderUserId() + " · " + Ui.money(bid.amountVnd())).toList());
        });
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
        Ui.receive(app.request(type, idPayload()), feedback, payload ->
                showAuction(Ui.parse(payload, "auction", AuctionDto.class)));
    }

    @FXML
    private void back() {
        app.show("auctions");
    }

    @FXML
    private void openResult() {
        app.result(auctionId);
    }

    @Override
    public void leave() {
        if (clock != null) {
            clock.stop();
        }
        if (eventListener != null && app.socket() != null) {
            app.socket().removeEventListener(eventListener);
            if (app.socket().isConnected() && app.user() != null) {
                app.request(RequestType.UNSUBSCRIBE_AUCTION, idPayload());
            }
        }
    }
}
