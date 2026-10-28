package com.btl.auction.common.model;

import java.util.Objects;

public final class Vehicle extends Item {
    private final String make;

    public Vehicle(long id, long sellerId, String name, String description,
                   long startingPriceVnd, String make) {
        super(id, sellerId, name, description, startingPriceVnd);
        this.make = Objects.requireNonNull(make, "make");
    }

    @Override
    public ItemCategory getCategory() {
        return ItemCategory.VEHICLE;
    }

    @Override
    public String getDetail() {
        return make;
    }
}
