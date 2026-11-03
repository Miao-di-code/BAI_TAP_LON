package com.btl.auction.server.service;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class AuctionManager {
    private static final AuctionManager INSTANCE = new AuctionManager();
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    private AuctionManager() {
    }

    public static AuctionManager getInstance() {
        return INSTANCE;
    }

    public <T> T withAuctionLock(long auctionId, Callable<T> action) throws Exception {
        if (auctionId <= 0) {
            throw new IllegalArgumentException("auctionId must be positive");
        }
        Objects.requireNonNull(action, "action");
        ReentrantLock lock = locks.computeIfAbsent(auctionId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.call();
        } finally {
            lock.unlock();
        }
    }
}
