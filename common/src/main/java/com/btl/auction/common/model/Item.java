package com.btl.auction.common.model;

import java.util.Objects;

public abstract class Item extends Entity {
    private final long sellerId;
    private final String name;
    private final String description;
    private final long startingPriceVnd;

    protected Item(long id, long sellerId, String name, String description, long startingPriceVnd) {
        super(id);
        if (sellerId <= 0 || startingPriceVnd < 0) {
            throw new IllegalArgumentException("invalid seller or starting price");
        }
        this.sellerId = sellerId;
        this.name = Objects.requireNonNull(name, "name");
        this.description = Objects.requireNonNull(description, "description");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.startingPriceVnd = startingPriceVnd;
    }

    public final long getSellerId() {
        return sellerId;
    }

    public final String getName() {
        return name;
    }

    public final String getDescription() {
        return description;
    }

    public final long getStartingPriceVnd() {
        return startingPriceVnd;
    }

    public abstract ItemCategory getCategory();

    public abstract String getDetail();
}
