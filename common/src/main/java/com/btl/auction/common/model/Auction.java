package com.btl.auction.common.model;

import java.util.Objects;

public final class Auction extends Entity {
    private final long itemId;
    private final long startAtEpochMs;
    private final long endAtEpochMs;
    private final long currentPriceVnd;
    private final Long leaderUserId;
    private final AuctionStatus status;

    public Auction(long id, long itemId, long startAtEpochMs, long endAtEpochMs,
                   long currentPriceVnd, Long leaderUserId, AuctionStatus status) {
        super(id);
        if (itemId <= 0 || startAtEpochMs < 0 || endAtEpochMs <= startAtEpochMs
                || currentPriceVnd < 0 || (leaderUserId != null && leaderUserId <= 0)) {
            throw new IllegalArgumentException("invalid auction data");
        }
        this.itemId = itemId;
        this.startAtEpochMs = startAtEpochMs;
        this.endAtEpochMs = endAtEpochMs;
        this.currentPriceVnd = currentPriceVnd;
        this.leaderUserId = leaderUserId;
        this.status = Objects.requireNonNull(status, "status");
    }

    public long getItemId() {
        return itemId;
    }

    public long getStartAtEpochMs() {
        return startAtEpochMs;
    }

    public long getEndAtEpochMs() {
        return endAtEpochMs;
    }

    public long getCurrentPriceVnd() {
        return currentPriceVnd;
    }

    public Long getLeaderUserId() {
        return leaderUserId;
    }

    public AuctionStatus getStatus() {
        return status;
    }

    public boolean acceptsBidsAt(long nowEpochMs) {
        return status == AuctionStatus.RUNNING
                && nowEpochMs >= startAtEpochMs && nowEpochMs < endAtEpochMs;
    }

    public Auction transitionTo(AuctionStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("invalid auction status transition");
        }
        return new Auction(getId(), itemId, startAtEpochMs, endAtEpochMs,
                currentPriceVnd, leaderUserId, next);
    }
}
