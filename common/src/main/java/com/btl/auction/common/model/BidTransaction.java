package com.btl.auction.common.model;

public final class BidTransaction extends Entity {
    private final long auctionId;
    private final long bidderUserId;
    private final long amountVnd;
    private final long createdAtEpochMs;

    public BidTransaction(long id, long auctionId, long bidderUserId,
                          long amountVnd, long createdAtEpochMs) {
        super(id);
        if (auctionId <= 0 || bidderUserId <= 0 || amountVnd <= 0 || createdAtEpochMs < 0) {
            throw new IllegalArgumentException("invalid bid data");
        }
        this.auctionId = auctionId;
        this.bidderUserId = bidderUserId;
        this.amountVnd = amountVnd;
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public long getAuctionId() {
        return auctionId;
    }

    public long getBidderUserId() {
        return bidderUserId;
    }

    public long getAmountVnd() {
        return amountVnd;
    }

    public long getCreatedAtEpochMs() {
        return createdAtEpochMs;
    }
}
