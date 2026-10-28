package com.btl.auction.common.model;

import java.util.Objects;

public final class Electronics extends Item {
    private final String manufacturer;

    public Electronics(long id, long sellerId, String name, String description,
                       long startingPriceVnd, String manufacturer) {
        super(id, sellerId, name, description, startingPriceVnd);
        this.manufacturer = Objects.requireNonNull(manufacturer, "manufacturer");
    }

    @Override
    public ItemCategory getCategory() {
        return ItemCategory.ELECTRONICS;
    }

    @Override
    public String getDetail() {
        return manufacturer;
    }
}
