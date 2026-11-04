package com.btl.auction.server.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.JsonCodec;
import com.btl.auction.common.protocol.LineFraming;
import com.btl.auction.common.protocol.RequestMessage;
import com.btl.auction.common.protocol.RequestType;
import com.btl.auction.common.protocol.ResponseMessage;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SocketServerPingTest {
    @Test
    void routesSeveralRequestsOverOneConnection() throws Exception {
        try (SocketServer server = new SocketServer(0)) {
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try {
                    server.serve();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            try (Socket client = new Socket("127.0.0.1", server.port())) {
                client.setSoTimeout(2000);
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                        client.getOutputStream(), StandardCharsets.UTF_8));
                output.write(JsonCodec.toJson(new RequestMessage(
                        "first", RequestType.PING, null, new JsonObject())));
                output.newLine();
                output.write(JsonCodec.toJson(new RequestMessage(
                        "second", RequestType.REGISTER, null, new JsonObject())));
                output.newLine();
                output.flush();
                ResponseMessage first = JsonCodec.parseResponse(LineFraming.readLine(
                        client.getInputStream()));
                ResponseMessage second = JsonCodec.parseResponse(LineFraming.readLine(
                        client.getInputStream()));
                assertEquals("first", first.requestId());
                assertTrue(first.success());
                assertEquals("second", second.requestId());
                assertEquals(ErrorCode.UNSUPPORTED_REQUEST, second.errorCode());
            } finally {
                server.close();
            }
            serving.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void malformedRequestDoesNotPreventTheNextPing() throws Exception {
        try (SocketServer server = new SocketServer(0)) {
            CompletableFuture<Void> serving = CompletableFuture.runAsync(() -> {
                try {
                    server.serve();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            try {
                ResponseMessage malformed = request(server, "{bad json}");
                assertFalse(malformed.success());
                assertEquals(ErrorCode.INVALID_REQUEST, malformed.errorCode());

                ResponseMessage response = request(server, JsonCodec.toJson(new RequestMessage(
                        "ping-1", RequestType.PING, null, new JsonObject())));
                assertEquals("ping-1", response.requestId());
                assertTrue(response.success());
                assertTrue(response.payload().get("pong").getAsBoolean());
                assertFalse(response.payload().has("errorCode"));
            } finally {
                server.close();
            }
            serving.get(2, TimeUnit.SECONDS);
        }
    }

    private static ResponseMessage request(SocketServer server, String json) throws Exception {
        try (Socket client = new Socket("127.0.0.1", server.port())) {
            client.setSoTimeout(2000);
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                    client.getOutputStream(), StandardCharsets.UTF_8));
            output.write(json);
            output.newLine();
            output.flush();
            return JsonCodec.parseResponse(LineFraming.readLine(client.getInputStream()));
        }
    }
}
