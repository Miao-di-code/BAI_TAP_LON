package com.btl.auction.common.protocol;

public enum ErrorCode {
    INVALID_REQUEST,
    UNSUPPORTED_REQUEST,
    VALIDATION_ERROR,
    AUTH_REQUIRED,
    AUTH_FAILED,
    FORBIDDEN,
    NOT_FOUND,
    AUCTION_NOT_RUNNING,
    BID_TOO_LOW,
    CONFLICT,
    INTERNAL_ERROR
}
