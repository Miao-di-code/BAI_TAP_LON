package com.btl.auction.server.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.RequestMessage;
import com.btl.auction.common.protocol.RequestType;
import com.btl.auction.common.protocol.ResponseMessage;
import com.btl.auction.server.db.Database;
import com.btl.auction.server.event.AuctionEventPublisher;
import com.btl.auction.server.service.AuctionService;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestDispatcherTest {
    @TempDir Path directory;

    @Test
    void returnsClearErrorsForUnavailableTypeAndBadPayload() {
        RequestDispatcher dispatcher = new RequestDispatcher();
        ResponseMessage ping = dispatcher.dispatch(request("1", RequestType.PING, new JsonObject()));
        assertEquals("1", ping.requestId());
        assertTrue(ping.payload().get("pong").getAsBoolean());

        ResponseMessage unsupported = dispatcher.dispatch(
                request("2", RequestType.REGISTER, new JsonObject()));
        assertFalse(unsupported.success());
        assertEquals("2", unsupported.requestId());
        assertEquals(ErrorCode.UNSUPPORTED_REQUEST, unsupported.errorCode());

        ResponseMessage unavailable = dispatcher.dispatch(
                request("3", RequestType.GET_AUCTIONS, new JsonObject()));
        assertEquals(ErrorCode.UNSUPPORTED_REQUEST, unavailable.errorCode());
    }

    @Test
    void routesAuctionQueriesToAuctionService() throws Exception {
        Database database = new Database(directory.resolve("auction.db"));
        String schema;
        try (InputStream input = getClass().getResourceAsStream("/schema.sql")) {
            schema = new String(java.util.Objects.requireNonNull(input).readAllBytes(),
                    StandardCharsets.UTF_8);
        }
        try (Connection connection = database.openConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : schema.split(";")) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
        AuctionService auctions = new AuctionService(database, Clock.systemUTC(),
                new AuctionEventPublisher());
        RequestDispatcher dispatcher = new RequestDispatcher(auctions);

        ResponseMessage list = dispatcher.dispatch(
                request("list-1", RequestType.GET_AUCTIONS, new JsonObject()));
        assertTrue(list.success());
        assertEquals(0, list.payload().getAsJsonArray("auctions").size());

        JsonObject invalid = new JsonObject();
        invalid.addProperty("auctionId", "abc");
        ResponseMessage detail = dispatcher.dispatch(
                request("detail-1", RequestType.GET_AUCTION_DETAIL, invalid));
        assertEquals(ErrorCode.VALIDATION_ERROR, detail.errorCode());
        assertEquals("detail-1", detail.requestId());
    }

    private static RequestMessage request(String id, RequestType type, JsonObject payload) {
        return new RequestMessage(id, type, null, payload);
    }
}
