package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy.Decision;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy.Decision.Reason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CaptureRulesTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static CaptureRules.RegionAt region(boolean actorHasPart, boolean ownerHasPart) {
        return new CaptureRules.RegionAt("plot", actorHasPart, ownerHasPart, false);
    }

    private static Decision decide(UUID owner, boolean override, CaptureRules.RegionAt... regions) {
        return CaptureRules.decide(ACTOR, owner, override, List.of(regions));
    }

    @Test
    void aRegionMemberVaultsTheContainerOfSomeoneWithNoPartInTheRegion() {
        Decision decision = decide(OWNER, false, region(true, false));
        Assertions.assertTrue(decision.allowed());
        Assertions.assertEquals(Reason.ALLOWED, decision.reason());
        Assertions.assertEquals(OWNER, decision.containerOwner());
    }

    @Test
    void aRegionMemberCannotVaultACoMembersContainer() {
        Decision decision = decide(OWNER, false, region(true, true));
        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals(Reason.CONTAINER_OWNER_IN_OVERLAP, decision.reason());
    }

    @Test
    void aRegionMemberCannotVaultTheirOwnContainerThere() {
        Decision decision = decide(ACTOR, false, region(true, true));
        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals(Reason.OWNER_SELF_IN_REGION, decision.reason());
    }

    @Test
    void notEvenTheOverrideVaultsAMembersOwnContainerInTheirRegion() {
        Assertions.assertFalse(decide(ACTOR, true, region(true, true)).allowed());
    }

    @Test
    void someoneWithNoPartInTheRegionCannotVaultAnothersContainer() {
        Decision decision = decide(OWNER, false, region(false, false));
        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals(Reason.NOT_INVOLVED_NOT_OWNER, decision.reason());
    }

    @Test
    void someoneWithNoPartInTheRegionMayVaultTheirOwnContainerThere() {
        Assertions.assertTrue(decide(ACTOR, false, region(false, false)).allowed());
    }

    @Test
    void aContainerOutsideEveryRegionNeedsTheOverride() {
        Decision without = decide(OWNER, false);
        Assertions.assertFalse(without.allowed());
        Assertions.assertEquals(Reason.NOT_IN_REGION, without.reason());

        Assertions.assertTrue(decide(OWNER, true).allowed());
    }

    @Test
    void aContainerNobodyOwnsIsNeverVaulted() {
        Decision inRegion = decide(null, false, region(true, false));
        Assertions.assertFalse(inRegion.allowed());
        Assertions.assertEquals(Reason.UNPROTECTED_NO_OVERRIDE, inRegion.reason());
        Assertions.assertNull(inRegion.containerOwner());

        // The override lifts the region rules, not the need for someone to own the vault.
        Assertions.assertFalse(decide(null, true, region(true, false)).allowed());
    }

    @Test
    void theOverrideLiftsTheRegionRules() {
        Assertions.assertTrue(decide(OWNER, true, region(false, false)).allowed());
        Assertions.assertTrue(decide(OWNER, true, region(true, true)).allowed());
    }

    @Test
    void aPartInAnEnclosingRegionAloneDoesNotCount() {
        // A city member is not thereby a member of every plot inside the city.
        CaptureRules.RegionAt city = new CaptureRules.RegionAt("city", true, false, true);
        CaptureRules.RegionAt plot = new CaptureRules.RegionAt("plot", false, false, false);
        Decision decision = decide(OWNER, false, city, plot);
        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals(Reason.NOT_INVOLVED_NOT_OWNER, decision.reason());
    }

    @Test
    void anOwnersPartInAnEnclosingRegionStillProtectsTheirContainer() {
        CaptureRules.RegionAt city = new CaptureRules.RegionAt("city", false, true, true);
        CaptureRules.RegionAt plot = new CaptureRules.RegionAt("plot", true, false, false);
        Decision decision = decide(OWNER, false, city, plot);
        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals(Reason.CONTAINER_OWNER_IN_OVERLAP, decision.reason());
    }

    @Test
    void theDecisionListsEveryRegionAtTheBlock() {
        Decision decision = decide(OWNER, false, region(true, false));
        Assertions.assertEquals(List.of(new VaultCapturePolicy.RegionStatus("plot", true, false)), decision.regionStatuses());
    }
}
