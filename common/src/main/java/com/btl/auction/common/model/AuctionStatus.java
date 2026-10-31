package com.btl.auction.common.model;

public enum AuctionStatus {
    OPEN, RUNNING, FINISHED, PAID, CANCELED;

    public boolean canTransitionTo(AuctionStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case OPEN -> next == RUNNING;
            case RUNNING -> next == FINISHED;
            case FINISHED -> next == PAID || next == CANCELED;
            case PAID, CANCELED -> false;
        };
    }
}
