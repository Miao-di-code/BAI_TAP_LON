package com.btl.auction.server.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.btl.auction.common.protocol.EventMessage;
import com.btl.auction.common.protocol.EventType;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuctionEventPublisherTest {
    @Test
    void notifiesOnlySubscribersOfMatchingAuction() {
        AuctionEventPublisher publisher = new AuctionEventPublisher();
        AtomicInteger count = new AtomicInteger();
        AuctionListener listener = event -> count.incrementAndGet();
        publisher.subscribe(1, listener);
        publisher.publish(new EventMessage(EventType.BID_UPDATED, 2, new JsonObject()));
        publisher.publish(new EventMessage(EventType.BID_UPDATED, 1, new JsonObject()));
        publisher.unsubscribe(1, listener);
        publisher.publish(new EventMessage(EventType.AUCTION_FINISHED, 1, new JsonObject()));
        assertEquals(1, count.get());
    }
}
