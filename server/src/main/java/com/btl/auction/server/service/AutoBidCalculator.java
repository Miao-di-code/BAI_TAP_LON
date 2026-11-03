package com.btl.auction.server.service;

import com.btl.auction.common.dto.AutoBidDto;
import java.util.Comparator;
import java.util.List;

/** One bounded price calculation replaces a potentially unbounded bid exchange. */
public final class AutoBidCalculator {
    public record Decision(AutoBidDto bidder, long amountVnd) {
    }

    private AutoBidCalculator() {
    }

    public static Decision next(List<AutoBidDto> configurations, long price, Long leaderId) {
        List<AutoBidDto> ranked = configurations.stream().sorted(
                Comparator.comparingLong(AutoBidDto::maxBidVnd).reversed()
                        .thenComparingLong(AutoBidDto::createdAtEpochMs)
                        .thenComparingLong(AutoBidDto::id)).toList();
        if (ranked.isEmpty()) {
            return null;
        }
        AutoBidDto winner = ranked.get(0);
        if (winner.maxBidVnd() <= price) {
            return null;
        }
        long rivalMax = ranked.size() > 1 ? ranked.get(1).maxBidVnd() : 0;
        if (leaderId != null && leaderId == winner.bidderUserId() && rivalMax <= price) {
            return null;
        }
        long needed = saturatedAdd(Math.max(price, rivalMax), winner.incrementVnd());
        long amount = Math.min(winner.maxBidVnd(), needed);
        return amount > price ? new Decision(winner, amount) : null;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
