package com.btl.auction.common.protocol;

import com.google.gson.JsonObject;
import java.util.Objects;

public record RequestMessage(String requestId, RequestType type, String token,
                             JsonObject payload) {
    public RequestMessage {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId is required");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }
}
