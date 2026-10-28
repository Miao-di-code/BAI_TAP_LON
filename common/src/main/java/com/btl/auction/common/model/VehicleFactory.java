package com.btl.auction.common.model;

public final class VehicleFactory extends ItemFactory {
    @Override
    public Item create(long id, long sellerId, String name, String description,
                       long startingPriceVnd, String detail) {
        return new Vehicle(id, sellerId, name, description, startingPriceVnd, detail);
    }
}
