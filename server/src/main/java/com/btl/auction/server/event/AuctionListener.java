package com.btl.auction.server.event;

import com.btl.auction.common.protocol.EventMessage;

@FunctionalInterface
public interface AuctionListener { //MTK: Observer
    void onAuctionEvent(EventMessage event); //Bất kì class nào muốn nhận sk từ phiên đấu giá đều phải ký vào hợp đồng này->tự định nghĩa onAuctionEvent sẽ làm j.
}
