package com.btl.auction.common.model;

import java.util.Objects;

public final class Art extends Item {
    private final String artist;

    public Art(long id, long sellerId, String name, String description,
               long startingPriceVnd, String artist) {
        super(id, sellerId, name, description, startingPriceVnd);
        this.artist = Objects.requireNonNull(artist, "artist");
    }

    @Override
    public ItemCategory getCategory() {
        return ItemCategory.ART;
    }

    @Override
    public String getDetail() {
        return artist;
    }
}
