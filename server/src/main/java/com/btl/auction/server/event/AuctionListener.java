package com.btl.auction.server.event;

import com.btl.auction.common.protocol.EventMessage;

@FunctionalInterface
public interface AuctionListener {
    void onAuctionEvent(EventMessage event);
}
