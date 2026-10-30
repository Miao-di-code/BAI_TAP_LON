package com.btl.auction.common.protocol;

import com.google.gson.JsonObject;
import java.util.Objects;

public record ResponseMessage(String requestId, boolean success, JsonObject payload,
                              ErrorCode errorCode, String message) {
    public ResponseMessage {
        if (success) {
            Objects.requireNonNull(payload, "payload");
            if (errorCode != null || message != null) {
                throw new IllegalArgumentException("success response cannot contain error");
            }
        } else {
            Objects.requireNonNull(errorCode, "errorCode");
            Objects.requireNonNull(message, "message");
        }
    }

    public static ResponseMessage ok(String requestId, JsonObject payload) {
        return new ResponseMessage(requestId, true, payload, null, null);
    }

    public static ResponseMessage error(String requestId, ErrorCode code, String message) {
        return new ResponseMessage(requestId, false, null, code, message);
    }
}
