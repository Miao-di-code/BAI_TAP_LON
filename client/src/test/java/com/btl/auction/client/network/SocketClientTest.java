package com.btl.auction.client.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btl.auction.common.dto.AuctionDto;
import com.btl.auction.common.model.AuctionStatus;
import com.btl.auction.common.protocol.EventMessage;
import com.btl.auction.common.protocol.EventType;
import com.btl.auction.common.protocol.JsonCodec;
import com.btl.auction.common.protocol.LineFraming;
import com.btl.auction.common.protocol.RequestType;
import com.btl.auction.common.protocol.ResponseMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SocketClientTest {
    @Test
    void pendingRequestFailsWhenConnectionDrops() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             SocketClient client = new SocketClient()) {
            CompletableFuture<?> server = CompletableFuture.runAsync(() -> {
                try (Socket socket = listener.accept();
                     BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
                    LineFraming.readLine(input);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            client.connect("127.0.0.1", listener.getLocalPort());
            CompletableFuture<ResponseMessage> pending = client.request(RequestType.PING,
                    null, new JsonObject());
            assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertFalse(client.isConnected());
            server.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void matchesOutOfOrderResponsesAndRejectsOlderAuctionEvents() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             SocketClient client = new SocketClient()) {
            CountDownLatch updatedEvents = new CountDownLatch(2);
            CountDownLatch disconnected = new CountDownLatch(1);
            AtomicInteger events = new AtomicInteger();
            client.addEventListener(event -> {
                events.incrementAndGet();
                updatedEvents.countDown();
            });
            client.addDisconnectListener(error -> disconnected.countDown());
            CompletableFuture<?> server = CompletableFuture.runAsync(() -> {
                try (Socket socket = listener.accept();
                     BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                     BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                             socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    String first = LineFraming.readLine(input);
                    String second = LineFraming.readLine(input);
                    String firstId = JsonCodec.parseRequest(first).requestId();
                    String secondId = JsonCodec.parseRequest(second).requestId();
                    JsonObject secondPayload = new JsonObject();
                    secondPayload.addProperty("marker", "second");
                    write(output, ResponseMessage.ok(secondId, secondPayload));
                    JsonObject firstPayload = new JsonObject();
                    firstPayload.add("auction", new Gson().toJsonTree(auction(120)));
                    firstPayload.addProperty("marker", "first");
                    write(output, ResponseMessage.ok(firstId, firstPayload));
                    write(output, event(110));
                    write(output, event(130));
                    write(output, extended(130, 1100));
                    write(output, event(130));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            client.connectAsync("127.0.0.1", listener.getLocalPort()).get(5, TimeUnit.SECONDS);
            CompletableFuture<ResponseMessage> first = client.request(RequestType.GET_AUCTION_DETAIL,
                    null, new JsonObject());
            CompletableFuture<ResponseMessage> second = client.request(RequestType.PING,
                    null, new JsonObject());
            assertEquals("second", second.get(5, TimeUnit.SECONDS).payload().get("marker").getAsString());
            assertEquals("first", first.get(5, TimeUnit.SECONDS).payload().get("marker").getAsString());
            assertTrue(updatedEvents.await(5, TimeUnit.SECONDS));
            assertEquals(2, events.get());
            assertEquals(130, client.latestAuction(1).currentPriceVnd());
            assertEquals(1100, client.latestAuction(1).endAtEpochMs());
            assertTrue(disconnected.await(5, TimeUnit.SECONDS));
            assertFalse(client.isConnected());
            server.get(5, TimeUnit.SECONDS);
        }
    }

    private static AuctionDto auction(long price) {
        return new AuctionDto(1, 1, 100, 1000, price, 2L, AuctionStatus.RUNNING);
    }

    private static EventMessage event(long price) {
        JsonObject payload = new JsonObject();
        payload.add("auction", new Gson().toJsonTree(auction(price)));
        return new EventMessage(EventType.BID_UPDATED, 1, payload);
    }

    private static EventMessage extended(long price, long end) {
        JsonObject payload = new JsonObject();
        payload.add("auction", new Gson().toJsonTree(new AuctionDto(1, 1, 100, end,
                price, 2L, AuctionStatus.RUNNING)));
        return new EventMessage(EventType.AUCTION_EXTENDED, 1, payload);
    }

    private static void write(BufferedWriter writer, Object message) throws Exception {
        writer.write(JsonCodec.toJson(message));
        writer.newLine();
        writer.flush();
    }
}
