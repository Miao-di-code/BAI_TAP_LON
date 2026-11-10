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
}