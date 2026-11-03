package com.btl.auction.common.dto;

import com.btl.auction.common.model.AuctionStatus;

public record AuctionDto(long id, long itemId, long startAtEpochMs, long endAtEpochMs,
                         long currentPriceVnd, Long leaderUserId, AuctionStatus status) {
}
