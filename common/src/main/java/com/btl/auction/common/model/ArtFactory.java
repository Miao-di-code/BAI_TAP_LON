package com.btl.auction.common.model;

public final class ArtFactory extends ItemFactory {
    @Override
    public Item create(long id, long sellerId, String name, String description,
                       long startingPriceVnd, String detail) {
        return new Art(id, sellerId, name, description, startingPriceVnd, detail);
    }
}
