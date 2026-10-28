package com.btl.auction.common.model;

public enum ItemCategory {
    ELECTRONICS(new ElectronicsFactory()),
    ART(new ArtFactory()),
    VEHICLE(new VehicleFactory());

    private final ItemFactory factory;

    ItemCategory(ItemFactory factory) {
        this.factory = factory;
    }

    public ItemFactory factory() {
        return factory;
    }
}
