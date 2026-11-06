package com.btl.auction.server.service;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuctionScheduler implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(AuctionScheduler.class.getName());
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "auction-clock");
        thread.setDaemon(true);
        return thread;
    });

    public AuctionScheduler(AuctionService service, Clock clock) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(clock, "clock");
        executor.scheduleWithFixedDelay(() -> {
            try {
                service.closeExpired(clock.millis());
            } catch (RuntimeException ex) {
                LOGGER.log(Level.SEVERE, "Auction status refresh failed", ex);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
