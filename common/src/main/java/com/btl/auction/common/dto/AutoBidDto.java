package com.btl.auction.common.dto;

public record AutoBidDto(long id, long auctionId, long bidderUserId,
                         long maxBidVnd, long incrementVnd, long createdAtEpochMs) {
}
