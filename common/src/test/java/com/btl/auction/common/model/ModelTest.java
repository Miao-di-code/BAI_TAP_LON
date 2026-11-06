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
    void auctionChecksTimeWindowAndStatusTransitions() {
        Auction auction = new Auction(1, 2, 1_000, 2_000, 100, null, AuctionStatus.OPEN);
        assertFalse(auction.acceptsBidsAt(1_500));
        Auction running = auction.transitionTo(AuctionStatus.RUNNING);
        assertFalse(running.acceptsBidsAt(999));
        assertTrue(running.acceptsBidsAt(1_000));
        assertFalse(running.acceptsBidsAt(2_000));
        Auction finished = running.transitionTo(AuctionStatus.FINISHED);
        assertEquals(AuctionStatus.PAID, finished.transitionTo(AuctionStatus.PAID).getStatus());
        assertThrows(IllegalStateException.class, () -> running.transitionTo(AuctionStatus.PAID));
    }
}
