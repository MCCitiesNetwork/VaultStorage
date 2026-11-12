package com.minecraftcitiesnetwork.vaultstorage.capture;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DoubleChestRuleTest {

    @Test
    void aBlockThatIsNotAContainerIsOnlyUnprotected() {
        Assertions.assertEquals(DoubleChestRule.Action.UNPROTECT, DoubleChestRule.decide(false, true, false, true));
    }

    @Test
    void anEmptySingleContainerIsOnlyUnprotected() {
        Assertions.assertEquals(DoubleChestRule.Action.UNPROTECT, DoubleChestRule.decide(true, true, false, true));
    }

    @Test
    void aSingleContainerWithItemsIsCaptured() {
        Assertions.assertEquals(DoubleChestRule.Action.CAPTURE, DoubleChestRule.decide(true, false, false, true));
    }

    @Test
    void anEmptyHalfIsLeftAloneWhileTheOtherHalfHoldsItems() {
        // Unprotecting it would unprotect the full half too: Bolt keeps one protection for both.
        Assertions.assertEquals(DoubleChestRule.Action.OTHER_HALF_FIRST, DoubleChestRule.decide(true, true, true, false));
    }

    @Test
    void aDoubleChestWithBothHalvesEmptyIsOnlyUnprotected() {
        Assertions.assertEquals(DoubleChestRule.Action.UNPROTECT, DoubleChestRule.decide(true, true, true, true));
    }

    @Test
    void aHalfWithItemsIsCapturedWhateverTheOtherHalfHolds() {
        Assertions.assertEquals(DoubleChestRule.Action.CAPTURE, DoubleChestRule.decide(true, false, true, true));
        Assertions.assertEquals(DoubleChestRule.Action.CAPTURE, DoubleChestRule.decide(true, false, true, false));
    }
}
