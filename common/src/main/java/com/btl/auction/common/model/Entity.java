package com.btl.auction.common.model;

public abstract class Entity {
    private final long id;

    protected Entity(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        this.id = id;
    }

    public final long getId() {
        return id;
    }
}
