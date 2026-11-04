package com.btl.auction.common.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Objects;

public final class JsonCodec {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private JsonCodec() {
    }

    public static String toJson(Object message) {
        return GSON.toJson(Objects.requireNonNull(message, "message"));
    }

    public static RequestMessage parseRequest(String line) {
        try {
            JsonElement root = JsonParser.parseString(Objects.requireNonNull(line, "line"));
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("request must be an object");
            }
            JsonObject object = root.getAsJsonObject();
            String requestId = requiredString(object, "requestId");
            RequestType type = RequestType.valueOf(requiredString(object, "type"));
            JsonElement tokenElement = object.get("token");
            String token = tokenElement == null || tokenElement.isJsonNull()
                    ? null : tokenElement.getAsString();
            JsonElement payloadElement = object.get("payload");
            if (payloadElement == null || !payloadElement.isJsonObject()) {
                throw new IllegalArgumentException("payload must be an object");
            }
            return new RequestMessage(requestId, type, token, payloadElement.getAsJsonObject());
        } catch (RuntimeException ex) {
            throw new InvalidProtocolException("invalid request JSON", ex);
        }
    }

    public static ResponseMessage parseResponse(String line) {
        try {
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            JsonElement id = object.get("requestId");
            String requestId = id == null || id.isJsonNull() ? null : id.getAsString();
            boolean success = object.get("success").getAsBoolean();
            if (success) {
                return ResponseMessage.ok(requestId, object.getAsJsonObject("payload"));
            }
            return ResponseMessage.error(requestId,
                    ErrorCode.valueOf(requiredString(object, "errorCode")),
                    requiredString(object, "message"));
        } catch (RuntimeException ex) {
            throw new InvalidProtocolException("invalid response JSON", ex);
        }
    }

    public static EventMessage parseEvent(String line) {
        try {
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            return new EventMessage(EventType.valueOf(requiredString(object, "type")),
                    object.get("auctionId").getAsLong(), object.getAsJsonObject("payload"));
        } catch (RuntimeException ex) {
            throw new InvalidProtocolException("invalid event JSON", ex);
        }
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return element.getAsString();
    }
}
