package com.btl.auction.server.event;

import com.btl.auction.common.protocol.EventMessage;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuctionEventPublisher {
    private static final Logger LOGGER = Logger.getLogger(AuctionEventPublisher.class.getName());
    private static final class PendingEvents {
        private final ConcurrentLinkedQueue<EventMessage> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean();
    }

    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<AuctionListener>> listeners =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, PendingEvents> pending = new ConcurrentHashMap<>();

    public void enqueue(EventMessage event) {
        Objects.requireNonNull(event, "event");
        pending.computeIfAbsent(event.auctionId(), ignored -> new PendingEvents()).queue.add(event);
    }

    public void drain(long auctionId) {
        PendingEvents state = pending.get(auctionId);
        if (state == null || !state.draining.compareAndSet(false, true)) {
            return;
        }
        for (;;) {
            try {
                EventMessage event;
                while ((event = state.queue.poll()) != null) {
                    publish(event);
                }
            } finally {
                state.draining.set(false);
            }
            if (state.queue.isEmpty() || !state.draining.compareAndSet(false, true)) {
                return;
            }
        }
    }

    public void subscribe(long auctionId, AuctionListener listener) {
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        listeners.computeIfAbsent(auctionId, ignored -> new CopyOnWriteArrayList<>())
                .addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    public void unsubscribe(long auctionId, AuctionListener listener) {
        CopyOnWriteArrayList<AuctionListener> auctionListeners = listeners.get(auctionId);
        if (auctionListeners != null) {
            auctionListeners.remove(listener);
            if (auctionListeners.isEmpty()) {
                listeners.remove(auctionId, auctionListeners);
            }
        }
    }

    public void publish(EventMessage event) {
        Objects.requireNonNull(event, "event");
        CopyOnWriteArrayList<AuctionListener> auctionListeners = listeners.get(event.auctionId());
        if (auctionListeners != null) {
            for (AuctionListener listener : auctionListeners) {
                try {
                    listener.onAuctionEvent(event);
                } catch (RuntimeException ex) {
                    LOGGER.log(Level.WARNING, "Auction listener failed", ex);
                }
            }
        }
    }
}
