package com.btl.auction.server.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.EventMessage;
import com.btl.auction.common.protocol.EventType;
import com.btl.auction.common.protocol.JsonCodec;
import com.btl.auction.common.protocol.LineFraming;
import com.btl.auction.common.protocol.RequestMessage;
import com.btl.auction.common.protocol.RequestType;
import com.btl.auction.common.protocol.ResponseMessage;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.server.dao.UserDao;
import com.btl.auction.server.db.Database;
import com.btl.auction.server.db.DatabaseInitializer;
import com.btl.auction.server.db.Transactions;
import com.btl.auction.server.service.PasswordHasher;
import com.btl.auction.server.service.AntiSnipingPolicy;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SocketServerIntegrationTest {
    @TempDir
    Path directory;

    @Test
    void finalEndToEndFlowSurvivesServerRestartAndKeepsWinner() throws Exception {
        MutableClock clock = new MutableClock(7_000_000);
        Path path = directory.resolve("final-e2e.db");
        long auctionId;
        long itemId;
        long winnerId;
        int configuredPort = Integer.getInteger("auction.e2e.port", 0);
        try (SocketServer server = new SocketServer(configuredPort, path, clock)) {
            server.start();
            try (Peer seller = new Peer(server.port());
                 Peer bidderA = new Peer(server.port());
                 Peer bidderB = new Peer(server.port())) {
                seller.registerAndLogin("e2e_seller", "SELLER");
                bidderA.registerAndLogin("e2e_bidder_a", "BIDDER");
                winnerId = bidderB.registerAndLogin("e2e_bidder_b", "BIDDER");
                itemId = seller.request(RequestType.CREATE_ITEM, payload("category", "ART",
                        "name", "E2E Painting", "description", "Canvas", "startingPriceVnd",
                        100, "detail", "Painter")).payload().getAsJsonObject("item")
                        .get("id").getAsLong();
                auctionId = seller.request(RequestType.CREATE_AUCTION, payload("itemId", itemId,
                        "startAtEpochMs", 7_000_000, "endAtEpochMs", 7_003_000))
                        .payload().getAsJsonObject("auction").get("id").getAsLong();
                for (Peer bidder : List.of(bidderA, bidderB)) {
                    assertTrue(bidder.request(RequestType.GET_AUCTION_DETAIL,
                            payload("auctionId", auctionId)).success());
                    assertTrue(bidder.request(RequestType.SUBSCRIBE_AUCTION,
                            payload("auctionId", auctionId)).success());
                }
                assertTrue(bidderA.request(RequestType.PLACE_BID,
                        payload("auctionId", auctionId, "amountVnd", 110)).success());
                bidderB.awaitEvent(EventType.BID_UPDATED, 110);
                assertTrue(bidderB.request(RequestType.PLACE_BID,
                        payload("auctionId", auctionId, "amountVnd", 120)).success());
                bidderA.awaitEvent(EventType.BID_UPDATED, 120);
                assertEquals(ErrorCode.BID_TOO_LOW, bidderA.request(RequestType.PLACE_BID,
                        payload("auctionId", auctionId, "amountVnd", 120)).errorCode());
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService workers = Executors.newFixedThreadPool(2);
                int accepted = 0;
                try {
                    Future<ResponseMessage> lower = workers.submit(() -> {
                        start.await();
                        return bidderA.request(RequestType.PLACE_BID,
                                payload("auctionId", auctionId, "amountVnd", 130));
                    });
                    Future<ResponseMessage> higher = workers.submit(() -> {
                        start.await();
                        return bidderB.request(RequestType.PLACE_BID,
                                payload("auctionId", auctionId, "amountVnd", 140));
                    });
                    start.countDown();
                    accepted += lower.get().success() ? 1 : 0;
                    accepted += higher.get().success() ? 1 : 0;
                    assertTrue(higher.get().success());
                } finally {
                    workers.shutdownNow();
                }
                bidderA.awaitEvent(EventType.BID_UPDATED, 140);
                bidderB.awaitEvent(EventType.BID_UPDATED, 140);
                assertEquals(2 + accepted, seller.request(RequestType.GET_BID_HISTORY,
                        payload("auctionId", auctionId)).payload().getAsJsonArray("bids").size());
                clock.set(7_003_000);
                bidderA.awaitEvent(EventType.AUCTION_FINISHED, -1);
                bidderB.awaitEvent(EventType.AUCTION_FINISHED, -1);
                JsonObject finished = seller.request(RequestType.GET_AUCTION_DETAIL,
                        payload("auctionId", auctionId)).payload().getAsJsonObject("auction");
                assertEquals("FINISHED", finished.get("status").getAsString());
                assertEquals(140, finished.get("currentPriceVnd").getAsLong());
                assertEquals(winnerId, finished.get("leaderUserId").getAsLong());
                assertEquals(ErrorCode.AUCTION_NOT_RUNNING, bidderA.request(RequestType.PLACE_BID,
                        payload("auctionId", auctionId, "amountVnd", 150)).errorCode());
            }
        }
        try (SocketServer restarted = new SocketServer(0, path, clock)) {
            restarted.start();
            try (Peer seller = new Peer(restarted.port())) {
                seller.login("e2e_seller");
                assertEquals(itemId, seller.request(RequestType.GET_ITEM, payload("itemId", itemId))
                        .payload().getAsJsonObject("item").get("id").getAsLong());
                JsonObject restored = seller.request(RequestType.GET_AUCTION_DETAIL,
                        payload("auctionId", auctionId)).payload().getAsJsonObject("auction");
                assertEquals("FINISHED", restored.get("status").getAsString());
                assertEquals(140, restored.get("currentPriceVnd").getAsLong());
                assertEquals(winnerId, restored.get("leaderUserId").getAsLong());
                assertTrue(seller.request(RequestType.GET_BID_HISTORY,
                        payload("auctionId", auctionId)).payload().getAsJsonArray("bids").size()
                        >= 3);
            }
        }
    }

    @Test
    void autoBidAndAntiSnipingBroadcastUseRealSocketAndCurrentEnd() throws Exception {
        MutableClock clock = new MutableClock(6_000_000);
        try (SocketServer server = new SocketServer(0, directory.resolve("advanced-network.db"),
                clock, new AntiSnipingPolicy(10_000, 20_000))) {
            server.start();
            try (Peer seller = new Peer(server.port());
                 Peer first = new Peer(server.port());
                 Peer second = new Peer(server.port())) {
                seller.registerAndLogin("seller_advanced", "SELLER");
                first.registerAndLogin("bidder_advanced_a", "BIDDER");
                second.registerAndLogin("bidder_advanced_b", "BIDDER");
                long itemId = seller.request(RequestType.CREATE_ITEM, payload("category", "ART",
                        "name", "Artwork", "description", "Canvas", "startingPriceVnd", 100,
                        "detail", "Artist")).payload().getAsJsonObject("item").get("id").getAsLong();
                long auctionId = seller.request(RequestType.CREATE_AUCTION,
                        payload("itemId", itemId, "startAtEpochMs", 6_000_000,
                                "endAtEpochMs", 6_020_000)).payload()
                        .getAsJsonObject("auction").get("id").getAsLong();
                first.request(RequestType.SUBSCRIBE_AUCTION, payload("auctionId", auctionId));
                second.request(RequestType.SUBSCRIBE_AUCTION, payload("auctionId", auctionId));
                assertEquals(ErrorCode.FORBIDDEN, seller.request(RequestType.SET_AUTO_BID,
                        payload("auctionId", auctionId, "maxBidVnd", 200,
                                "incrementVnd", 10)).errorCode());
                assertEquals(110, first.request(RequestType.SET_AUTO_BID,
                        payload("auctionId", auctionId, "maxBidVnd", 200,
                                "incrementVnd", 10)).payload().getAsJsonObject("auction")
                        .get("currentPriceVnd").getAsLong());
                second.awaitEvent(EventType.BID_UPDATED, 110);
                assertEquals(190, second.request(RequestType.SET_AUTO_BID,
                        payload("auctionId", auctionId, "maxBidVnd", 180,
                                "incrementVnd", 5)).payload().getAsJsonObject("auction")
                        .get("currentPriceVnd").getAsLong());
                assertEquals(180, second.request(RequestType.GET_AUTO_BID,
                        payload("auctionId", auctionId)).payload().getAsJsonObject("autoBid")
                        .get("maxBidVnd").getAsLong());
                first.awaitEvent(EventType.BID_UPDATED, 190);
                clock.set(6_010_000);
                ResponseMessage bid = second.request(RequestType.PLACE_BID,
                        payload("auctionId", auctionId, "amountVnd", 195));
                assertEquals(200, bid.payload().getAsJsonObject("auction")
                        .get("currentPriceVnd").getAsLong());
                assertEquals(6_040_000, bid.payload().getAsJsonObject("auction")
                        .get("endAtEpochMs").getAsLong());
                first.awaitEvent(EventType.AUCTION_EXTENDED, 200);
                second.awaitEvent(EventType.AUCTION_EXTENDED, 200);
                clock.set(6_020_000);
                assertEquals("RUNNING", seller.request(RequestType.GET_AUCTION_DETAIL,
                        payload("auctionId", auctionId)).payload().getAsJsonObject("auction")
                        .get("status").getAsString());
                clock.set(6_040_000);
                assertEquals("FINISHED", seller.request(RequestType.GET_AUCTION_DETAIL,
                        payload("auctionId", auctionId)).payload().getAsJsonObject("auction")
                        .get("status").getAsString());
                first.awaitEvent(EventType.AUCTION_FINISHED, 200);
                second.awaitEvent(EventType.AUCTION_FINISHED, 200);
            }
        }
    }

    @Test
    void adminUsesServerRoleForItemAndCompletion() throws Exception {
        Path dbPath = directory.resolve("admin-network.db");
        Database database = DatabaseInitializer.initialize(dbPath);
        Transactions.write(database, connection -> new UserDao().insert(connection, "localadmin",
                "Local Admin", PasswordHasher.hash("password123"), UserRole.ADMIN, 1_000));
        MutableClock clock = new MutableClock(5_000_000);
        try (SocketServer server = new SocketServer(0, dbPath, clock)) {
            server.start();
            try (Peer admin = new Peer(server.port());
                 Peer seller = new Peer(server.port());
                 Peer bidder = new Peer(server.port())) {
                assertEquals(ErrorCode.FORBIDDEN, admin.request(RequestType.REGISTER,
                        payload("username", "another_admin", "displayName", "Admin",
                                "password", "password123", "role", "ADMIN")).errorCode());
                admin.login("localadmin");
                seller.registerAndLogin("seller_admin", "SELLER");
                bidder.registerAndLogin("bidder_admin", "BIDDER");
                long itemId = seller.request(RequestType.CREATE_ITEM, payload("category", "ART",
                        "name", "Original", "description", "Old", "startingPriceVnd", 100,
                        "detail", "Artist")).payload().getAsJsonObject("item").get("id").getAsLong();
                assertEquals(1, admin.request(RequestType.LIST_ITEMS, new JsonObject())
                        .payload().getAsJsonArray("items").size());
                assertEquals("Updated", admin.request(RequestType.UPDATE_ITEM,
                        payload("itemId", itemId, "name", "Updated", "description", "New",
                                "startingPriceVnd", 200, "detail", "Artist"))
                        .payload().getAsJsonObject("item").get("name").getAsString());
                long auctionId = seller.request(RequestType.CREATE_AUCTION,
                        payload("itemId", itemId, "startAtEpochMs", 5_000_000,
                                "endAtEpochMs", 5_000_100)).payload()
                        .getAsJsonObject("auction").get("id").getAsLong();
                bidder.request(RequestType.PLACE_BID,
                        payload("auctionId", auctionId, "amountVnd", 250));
                clock.set(5_000_100);
                assertEquals("PAID", admin.request(RequestType.MARK_PAID,
                        payload("auctionId", auctionId)).payload().getAsJsonObject("auction")
                        .get("status").getAsString());
            }
        }
    }

    @Test
    void oversizedLineGetsStructuredErrorAndServerStaysAvailable() throws Exception {
        MutableClock clock = new MutableClock(4_000_000);
        try (SocketServer server = new SocketServer(0, directory.resolve("limit.db"), clock)) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.port());
                 BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                         socket.getOutputStream(), StandardCharsets.UTF_8))) {
                socket.setSoTimeout(5000);
                output.write("x".repeat(LineFraming.MAX_LINE_BYTES + 1));
                output.newLine();
                output.flush();
                ResponseMessage error = JsonCodec.parseResponse(LineFraming.readLine(input));
                assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
                assertEquals(null, LineFraming.readLine(input));
            }
            try (Peer healthy = new Peer(server.port())) {
                assertTrue(healthy.request(RequestType.PING, new JsonObject()).success());
            }
        }
    }

    @Test
    void sellerTwoBiddersRealtimeFinishAndInvalidJson() throws Exception {
        MutableClock clock = new MutableClock(2_000_000);
        try (SocketServer server = new SocketServer(0, directory.resolve("network.db"), clock)) {
            server.start();
            try (Peer seller = new Peer(server.port());
                 Peer bidderA = new Peer(server.port());
                 Peer bidderB = new Peer(server.port())) {
                seller.registerAndLogin("seller_net", "SELLER");
                long aId = bidderA.registerAndLogin("bidder_net_a", "BIDDER");
                bidderB.registerAndLogin("bidder_net_b", "BIDDER");
                JsonObject item = payload("category", "ART", "name", "Painting",
                        "description", "Canvas", "startingPriceVnd", 100, "detail", "Artist");
                long itemId = seller.request(RequestType.CREATE_ITEM, item).payload()
                        .getAsJsonObject("item").get("id").getAsLong();
                assertEquals(1, seller.request(RequestType.GET_MY_ITEMS, new JsonObject())
                        .payload().getAsJsonArray("items").size());
                long auctionId = seller.request(RequestType.CREATE_AUCTION,
                        payload("itemId", itemId, "startAtEpochMs", 2_000_100,
                                "endAtEpochMs", 2_001_000)).payload()
                        .getAsJsonObject("auction").get("id").getAsLong();
                bidderA.request(RequestType.SUBSCRIBE_AUCTION, payload("auctionId", auctionId));
                bidderB.request(RequestType.SUBSCRIBE_AUCTION, payload("auctionId", auctionId));
                clock.set(2_000_100);
                seller.request(RequestType.GET_AUCTION_DETAIL, payload("auctionId", auctionId));
                bidderA.awaitEvent(EventType.AUCTION_STARTED, -1);
                bidderB.awaitEvent(EventType.AUCTION_STARTED, -1);
                JsonObject firstBid = payload("auctionId", auctionId, "amountVnd", 110,
                        "bidderUserId", 99999, "role", "ADMIN");
                assertTrue(bidderA.request(RequestType.PLACE_BID, firstBid).success());
                assertEquals(110, bidderB.awaitEvent(EventType.BID_UPDATED, 110).payload()
                        .getAsJsonObject("auction").get("currentPriceVnd").getAsLong());
                assertEquals(aId, bidderB.request(RequestType.GET_BID_HISTORY,
                        payload("auctionId", auctionId)).payload().getAsJsonArray("bids")
                        .get(0).getAsJsonObject().get("bidderUserId").getAsLong());
                assertTrue(bidderB.request(RequestType.PLACE_BID,
                        payload("auctionId", auctionId, "amountVnd", 120)).success());
                assertEquals(120, bidderA.awaitEvent(EventType.BID_UPDATED, 120).payload()
                        .getAsJsonObject("auction").get("currentPriceVnd").getAsLong());
                assertEquals(ErrorCode.BID_TOO_LOW, bidderA.request(RequestType.PLACE_BID,
                        payload("auctionId", auctionId, "amountVnd", 120)).errorCode());
                bidderB.sendRaw("{invalid-json\n");
                assertEquals(ErrorCode.INVALID_REQUEST, bidderB.nextResponse().errorCode());
                assertTrue(bidderB.request(RequestType.PING, new JsonObject()).success());
                clock.set(2_001_000);
                assertEquals("FINISHED", bidderA.awaitEvent(EventType.AUCTION_FINISHED, -1)
                        .payload().getAsJsonObject("auction").get("status").getAsString());
                bidderB.awaitEvent(EventType.AUCTION_FINISHED, -1);
                assertEquals(120, seller.request(RequestType.GET_AUCTION_DETAIL,
                        payload("auctionId", auctionId)).payload().getAsJsonObject("auction")
                        .get("currentPriceVnd").getAsLong());
                assertEquals(2, seller.request(RequestType.GET_BIDS, payload("auctionId", auctionId))
                        .payload().getAsJsonArray("bids").size());
                bidderA.close();
                assertTrue(seller.request(RequestType.PING, new JsonObject()).success());
            }
        }
    }

    @Test
    void simultaneousSocketBidsKeepHighestPriceAndOnlyAcceptedEvents() throws Exception {
        MutableClock clock = new MutableClock(3_000_000);
        try (SocketServer server = new SocketServer(0, directory.resolve("concurrent.db"), clock)) {
            server.start();
            List<Peer> peers = new ArrayList<>();
            try {
                Peer seller = new Peer(server.port());
                peers.add(seller);
                seller.registerAndLogin("seller_many", "SELLER");
                long itemId = seller.request(RequestType.CREATE_ITEM, payload("category", "VEHICLE",
                        "name", "Car", "description", "Old", "startingPriceVnd", 100,
                        "detail", "Maker")).payload().getAsJsonObject("item").get("id").getAsLong();
                long auctionId = seller.request(RequestType.CREATE_AUCTION,
                        payload("itemId", itemId, "startAtEpochMs", 3_000_000,
                                "endAtEpochMs", 3_010_000)).payload()
                        .getAsJsonObject("auction").get("id").getAsLong();
                for (int i = 0; i < 8; i++) {
                    Peer bidder = new Peer(server.port());
                    peers.add(bidder);
                    bidder.registerAndLogin("bidder_many_" + i, "BIDDER");
                }
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService workers = Executors.newFixedThreadPool(8);
                try {
                    List<Future<ResponseMessage>> replies = new ArrayList<>();
                    for (int i = 0; i < 8; i++) {
                        Peer bidder = peers.get(i + 1);
                        long amount = 101 + i;
                        replies.add(workers.submit(() -> {
                            start.await();
                            return bidder.request(RequestType.PLACE_BID,
                                    payload("auctionId", auctionId, "amountVnd", amount));
                        }));
                    }
                    start.countDown();
                    int accepted = 0;
                    for (Future<ResponseMessage> reply : replies) {
                        if (reply.get().success()) {
                            accepted++;
                        } else {
                            assertEquals(ErrorCode.BID_TOO_LOW, reply.get().errorCode());
                        }
                    }
                    int history = seller.request(RequestType.GET_BID_HISTORY,
                            payload("auctionId", auctionId)).payload().getAsJsonArray("bids").size();
                    assertEquals(accepted, history);
                    assertEquals(108, seller.request(RequestType.GET_AUCTIONS, new JsonObject())
                            .payload().getAsJsonArray("auctions").get(0).getAsJsonObject()
                            .get("currentPriceVnd").getAsLong());
                } finally {
                    workers.shutdownNow();
                }
            } finally {
                for (Peer peer : peers) {
                    peer.close();
                }
            }
        }
    }

    private static JsonObject payload(Object... values) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < values.length; i += 2) {
            String name = (String) values[i];
            Object value = values[i + 1];
            if (value instanceof Number number) {
                result.addProperty(name, number);
            } else {
                result.addProperty(name, (String) value);
            }
        }
        return result;
    }

    private static final class Peer implements AutoCloseable {
        private final Socket socket;
        private final BufferedInputStream input;
        private final BufferedWriter output;
        private final ArrayDeque<EventMessage> events = new ArrayDeque<>();
        private String token;

        private Peer(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(5000);
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),
                    StandardCharsets.UTF_8));
        }

        private long registerAndLogin(String name, String role) throws IOException {
            ResponseMessage registered = request(RequestType.REGISTER, payload("username", name,
                    "displayName", name, "password", "password123", "role", role));
            assertTrue(registered.success());
            return login(name);
        }

        private long login(String name) throws IOException {
            ResponseMessage loggedIn = request(RequestType.LOGIN,
                    payload("username", name, "password", "password123"));
            token = loggedIn.payload().get("token").getAsString();
            assertNotNull(token);
            return loggedIn.payload().getAsJsonObject("user").get("id").getAsLong();
        }

        private ResponseMessage request(RequestType type, JsonObject payload) throws IOException {
            String id = UUID.randomUUID().toString();
            sendRaw(JsonCodec.toJson(new RequestMessage(id, type, token, payload)) + "\n");
            for (;;) {
                String line = LineFraming.readLine(input);
                if (line == null) {
                    throw new IOException("Server closed connection");
                }
                JsonObject frame = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                if (frame.has("requestId")) {
                    ResponseMessage response = JsonCodec.parseResponse(line);
                    assertEquals(id, response.requestId());
                    return response;
                }
                events.add(JsonCodec.parseEvent(line));
            }
        }

        private ResponseMessage nextResponse() throws IOException {
            for (;;) {
                String line = LineFraming.readLine(input);
                if (line == null) {
                    throw new IOException("Server closed connection");
                }
                JsonObject frame = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                if (frame.has("requestId")) {
                    return JsonCodec.parseResponse(line);
                }
                events.add(JsonCodec.parseEvent(line));
            }
        }

        private EventMessage awaitEvent(EventType type, long amount) throws IOException {
            for (;;) {
                EventMessage event = events.poll();
                if (event == null) {
                    String line = LineFraming.readLine(input);
                    if (line == null) {
                        throw new IOException("Server closed connection");
                    }
                    event = JsonCodec.parseEvent(line);
                }
                if (event.type() == type && (amount < 0 || event.payload()
                        .getAsJsonObject("auction").get("currentPriceVnd").getAsLong() == amount)) {
                    return event;
                }
            }
        }

        private void sendRaw(String line) throws IOException {
            output.write(line);
            output.flush();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong current;

        private MutableClock(long now) {
            current = new AtomicLong(now);
        }

        private void set(long now) {
            current.set(now);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(current.get());
        }
    }
}
