package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.placement.PlacementRules.Decision;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PlacementRulesTest {

    @Test
    void aGrantAtTheTargetAllowsPlacing() {
        Assertions.assertEquals(Decision.ALLOWED, PlacementRules.decide(true, false, () -> true));
    }

    @Test
    void withoutAGrantTheTargetIsRefused() {
        Assertions.assertEquals(Decision.NOT_HERE, PlacementRules.decide(true, false, () -> false));
    }

    @Test
    void theOverrideAloneIsEnoughAndNoGrantIsAsked() {
        Assertions.assertEquals(Decision.ALLOWED, PlacementRules.decide(true, true, () -> {
            throw new AssertionError("no grant should be asked");
        }));
    }

    @Test
    void neitherAGrantNorTheOverrideReplacesThePlacePermission() {
        Assertions.assertEquals(Decision.NO_PERMISSION, PlacementRules.decide(false, true, () -> true));
    }
}
