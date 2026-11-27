package com.minecraftcitiesnetwork.vaultstorage;

import java.util.logging.Logger;
import java.util.UUID;
import org.bukkit.Location;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PlacementGrantsTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Location TARGET = new Location(null, 10, 64, -20);

    @Test
    void withNoGrantNobodyMayPlace() {
        Assertions.assertFalse(new Integrations(Logger.getLogger("test")).mayPlace(PLAYER, TARGET));
    }

    @Test
    void oneGrantSayingYesIsEnough() {
        Integrations integrations = new Integrations(Logger.getLogger("test"));
        integrations.addPlacementGrant((player, target) -> false);
        Assertions.assertFalse(integrations.mayPlace(PLAYER, TARGET));
        integrations.addPlacementGrant((player, target) -> player.equals(PLAYER));
        Assertions.assertTrue(integrations.mayPlace(PLAYER, TARGET));
        Assertions.assertFalse(integrations.mayPlace(UUID.randomUUID(), TARGET));
    }

    @Test
    void aRemovedGrantNoLongerCounts() {
        Integrations integrations = new Integrations(Logger.getLogger("test"));
        com.minecraftcitiesnetwork.vaultstorage.placement.PlacementGrant grant = (player, target) -> true;
        integrations.addPlacementGrant(grant);
        integrations.removePlacementGrant(grant);
        Assertions.assertFalse(integrations.mayPlace(PLAYER, TARGET));
    }

    @Test
    void aGrantThatThrowsIsTreatedAsNo() {
        Integrations integrations = new Integrations(Logger.getLogger("test"));
        integrations.addPlacementGrant((player, target) -> {
            throw new IllegalStateException("claims unavailable");
        });
        Assertions.assertFalse(integrations.mayPlace(PLAYER, TARGET));
    }
}
