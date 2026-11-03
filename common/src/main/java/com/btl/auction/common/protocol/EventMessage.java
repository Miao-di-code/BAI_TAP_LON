package com.btl.auction.common.protocol;

import com.google.gson.JsonObject;
import java.util.Objects;

public record EventMessage(EventType type, long auctionId, JsonObject payload) {
    public EventMessage {
        Objects.requireNonNull(type, "type");
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        Objects.requireNonNull(payload, "payload");
    }
}
