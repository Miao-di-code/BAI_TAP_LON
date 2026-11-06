package com.btl.auction.server.network;

import com.btl.auction.common.protocol.EventMessage;
import com.btl.auction.server.event.AuctionEventPublisher;
import com.btl.auction.server.event.AuctionListener;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AuctionEventBroadcaster implements AuctionListener {
    private final AuctionEventPublisher publisher;
    private final ConcurrentHashMap<Long, Set<ClientSession>> subscribers = new ConcurrentHashMap<>();

    public AuctionEventBroadcaster(AuctionEventPublisher publisher) {
        this.publisher = publisher;
    }

    public synchronized void subscribe(long auctionId, ClientSession session) {
        Set<ClientSession> group = subscribers.computeIfAbsent(auctionId,
                ignored -> ConcurrentHashMap.newKeySet());
        group.add(session);
        publisher.subscribe(auctionId, this);
    }

    public synchronized void unsubscribe(long auctionId, ClientSession session) {
        Set<ClientSession> group = subscribers.get(auctionId);
        if (group != null) {
            group.remove(session);
            if (group.isEmpty()) {
                subscribers.remove(auctionId);
                publisher.unsubscribe(auctionId, this);
            }
        }
    }

    public synchronized void unsubscribeAll(ClientSession session) {
        for (long auctionId : Set.copyOf(subscribers.keySet())) {
            unsubscribe(auctionId, session);
        }
    }

    @Override
    public void onAuctionEvent(EventMessage event) {
        Set<ClientSession> group = subscribers.get(event.auctionId());
        if (group != null) {
            for (ClientSession session : group) {
                if (!session.send(event)) {
                    unsubscribe(event.auctionId(), session);
                }
            }
        }
    }
}
