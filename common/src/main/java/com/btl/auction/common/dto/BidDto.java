package com.btl.auction.common.dto;

public record BidDto(long id, long auctionId, long bidderUserId,
                     long amountVnd, long createdAtEpochMs) {
}
