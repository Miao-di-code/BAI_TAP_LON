package com.btl.auction.common.model;

public abstract class ItemFactory {
    public abstract Item create(long id, long sellerId, String name, String description,
                                long startingPriceVnd, String detail);
}
