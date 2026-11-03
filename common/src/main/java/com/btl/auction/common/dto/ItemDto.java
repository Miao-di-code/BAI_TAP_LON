package com.btl.auction.common.dto;

import com.btl.auction.common.model.ItemCategory;

public record ItemDto(long id, long sellerId, ItemCategory category, String name,
                      String description, long startingPriceVnd, String detail) {
}
