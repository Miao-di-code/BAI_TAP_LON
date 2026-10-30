package com.btl.auction.common.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class JsonCodecTest {
    @Test
    void requestRoundTripsWithUtf8Payload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", "Đấu giá tranh");
        RequestMessage request = new RequestMessage("r-1", RequestType.PING, null, payload);
        RequestMessage parsed = JsonCodec.parseRequest(JsonCodec.toJson(request));
        assertEquals(request.requestId(), parsed.requestId());
        assertEquals(request.type(), parsed.type());
        assertEquals("Đấu giá tranh", parsed.payload().get("name").getAsString());
    }

    @Test
    void malformedRequestIsRejected() {
        assertThrows(InvalidProtocolException.class,
                () -> JsonCodec.parseRequest("{\"requestId\":\"r-1\",\"type\":\"NOPE\",\"payload\":{}}"));
        assertThrows(InvalidProtocolException.class,
                () -> JsonCodec.parseRequest("{\"requestId\":\"r-1\",\"type\":\"PING\",\"payload\":null}"));
    }

    @Test
    void responseHasDistinctSuccessAndErrorShapes() {
        JsonObject payload = new JsonObject();
        payload.addProperty("pong", true);
        String success = JsonCodec.toJson(ResponseMessage.ok("r-1", payload));
        String failure = JsonCodec.toJson(ResponseMessage.error("r-1",
                ErrorCode.UNSUPPORTED_REQUEST, "Not implemented"));
        assertTrue(success.contains("\"success\":true"));
        assertTrue(success.contains("\"errorCode\":null"));
        assertTrue(failure.contains("\"success\":false"));
        assertTrue(failure.contains("\"errorCode\":\"UNSUPPORTED_REQUEST\""));
        assertEquals("r-1", JsonCodec.parseResponse(success).requestId());
        assertEquals(true, JsonCodec.parseResponse(success).payload().get("pong").getAsBoolean());
        assertEquals(ErrorCode.UNSUPPORTED_REQUEST, JsonCodec.parseResponse(failure).errorCode());
        assertThrows(InvalidProtocolException.class, () -> JsonCodec.parseResponse("{"));
    }

}
