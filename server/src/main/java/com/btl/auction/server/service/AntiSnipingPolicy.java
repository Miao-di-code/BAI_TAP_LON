package com.btl.auction.server.service;

public record AntiSnipingPolicy(long windowMs, long extensionMs) {
    public AntiSnipingPolicy {
        if (windowMs < 0 || extensionMs < 0 || (windowMs == 0) != (extensionMs == 0)) {
            throw new IllegalArgumentException("Window and extension must both be positive or zero");
        }
    }

    public static AntiSnipingPolicy disabled() {
        return new AntiSnipingPolicy(0, 0);
    }

    public long endAfterAcceptedBid(long now, long end) {
        if (windowMs == 0 || now >= end || now < end - windowMs) {
            return end;
        }
        return Math.addExact(end, extensionMs);
    }
}
