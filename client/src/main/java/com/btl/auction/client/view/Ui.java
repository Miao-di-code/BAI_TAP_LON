package com.btl.auction.client.view;

import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.protocol.ResponseMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Label;

public final class Ui {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    private Ui() {
    }

    public static <T> T parse(JsonObject payload, String key, Class<T> type) {
        return GSON.fromJson(payload.get(key), type);
    }

    public static String money(long amount) {
        return NumberFormat.getIntegerInstance(new Locale("vi", "VN")).format(amount) + " VND";
    }

    public static String date(long epochMs) {
        return DATE.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs),
                ZoneId.systemDefault()));
    }

    public static long epoch(String value) {
        return LocalDateTime.parse(value.trim(), DATE).atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }

    public static long positiveLong(String text) {
        long result = Long.parseLong(text.trim());
        if (result <= 0) {
            throw new NumberFormatException();
        }
        return result;
    }

    public static void receive(CompletableFuture<ResponseMessage> call, Label feedback,
                               Consumer<JsonObject> success) {
        call.whenComplete((response, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                feedback.setText("Không liên lạc được với server: " + rootMessage(failure));
            } else if (!response.success()) {
                feedback.setText(response.errorCode() + ": " + response.message());
            } else {
                feedback.setText("");
                success.accept(response.payload());
            }
        }));
    }

    public static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? "Lỗi kết nối" : root.getMessage();
    }

    public static String itemSummary(ItemDto item) {
        return item.name() + " · " + item.category();
    }

    public static String auctionSummary(AuctionDto auction, ItemDto item) {
        return (item == null ? "Sản phẩm #" + auction.itemId() : itemSummary(item))
                + " · " + money(auction.currentPriceVnd()) + " · " + auction.status();
    }
}
