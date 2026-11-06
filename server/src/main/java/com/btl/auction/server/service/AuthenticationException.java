package com.btl.auction.server.service;

import com.btl.auction.common.protocol.ErrorCode;

public final class AuthenticationException extends ServiceException {
    public AuthenticationException(String message) {
        super(ErrorCode.AUTH_FAILED, message);
    }
}
