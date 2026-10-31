package com.btl.auction.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModelTest {
    @Test
    void concreteFactoriesCreateSpecificTypesAndRejectInvalidPrice() {
        ItemFactory factory = ItemCategory.ART.factory();
        Item item = factory.create(1, 2, "Painting", "Oil", 100, "Mai");
        assertInstanceOf(Art.class, item);
        assertEquals(ItemCategory.ART, item.getCategory());
        assertEquals("Mai", item.getDetail());
        assertThrows(IllegalArgumentException.class, () ->
                factory.create(1, 2, "Painting", "Oil", -1, "Mai"));

        assertInstanceOf(Electronics.class, ItemCategory.ELECTRONICS.factory()
                .create(2, 2, "Radio", "Old", 100, "Brand"));
        assertInstanceOf(Vehicle.class, ItemCategory.VEHICLE.factory()
                .create(3, 2, "Bike", "Used", 100, "Make"));
    }

    @Test
    void bidTransactionRejectsZeroAndNegativePrice() {
        assertThrows(IllegalArgumentException.class, () -> {
            new BidTransaction(1L, 42L, 99L, -50000L, 1690000000L);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            new BidTransaction(2L, 42L, 99L, 0L, 1690000000L);
        });

        BidTransaction validBid = new BidTransaction(3L, 42L, 99L, 150000L, 1690000000L);
        assertEquals(150000L, validBid.getAmountVnd());
    }
}
