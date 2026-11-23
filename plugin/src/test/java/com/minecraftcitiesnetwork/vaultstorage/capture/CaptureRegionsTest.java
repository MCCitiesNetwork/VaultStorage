package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy.Decision;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy.Decision.Reason;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegionImp;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** How regions that overlap, nest or stack decide a capture, from their shapes to the decision. */
class CaptureRegionsTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static VaultRegion region(String id, UUID member, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new VaultRegionImp(id, member == null ? List.of() : List.of(member), List.of(),
                new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ), 0);
    }

    /** The regions among these that hold the point: what WorldGuard answers for cuboids. */
    private static List<VaultRegion> holding(int x, int y, int z, VaultRegion... regions) {
        return Stream.of(regions).filter(Objects::nonNull).filter(region -> {
            BoundingBox box = region.boundingBox();
            return x >= box.getMinX() && x <= box.getMaxX() && y >= box.getMinY() && y <= box.getMaxY()
                    && z >= box.getMinZ() && z <= box.getMaxZ();
        }).toList();
    }

    private static Decision decide(UUID owner, List<VaultRegion> regionsAtContainer) {
        return CaptureRules.decide(ACTOR, owner, false, CaptureRegions.at(ACTOR, owner, regionsAtContainer));
    }

    // Two regions that share a strip: west is x 0..10, east is x 8..20.

    @Test
    void whereTwoRegionsOverlapAMemberOfEitherVaultsAnOutsidersContainer() {
        VaultRegion west = region("west", ACTOR, 0, 0, 0, 10, 100, 10);
        VaultRegion east = region("east", null, 8, 0, 0, 20, 100, 10);

        Decision decision = decide(OWNER, holding(9, 64, 5, west, east));

        Assertions.assertTrue(decision.allowed());
        Assertions.assertEquals(2, decision.regionStatuses().size());
    }

    @Test
    void whereTwoRegionsOverlapTheOtherRegionsMemberKeepsTheirContainer() {
        VaultRegion west = region("west", ACTOR, 0, 0, 0, 10, 100, 10);
        VaultRegion east = region("east", OWNER, 8, 0, 0, 20, 100, 10);

        Decision decision = decide(OWNER, holding(9, 64, 5, west, east));

        Assertions.assertFalse(decision.allowed());
        Assertions.assertEquals(Reason.CONTAINER_OWNER_IN_OVERLAP, decision.reason());
    }

    @Test
    void outsideTheOverlapOnlyTheRegionTheContainerIsInCounts() {
        VaultRegion west = region("west", ACTOR, 0, 0, 0, 10, 100, 10);
        VaultRegion east = region("east", OWNER, 8, 0, 0, 20, 100, 10);

        // In west alone: the owner's part in east, next door, does not protect it.
        Assertions.assertTrue(decide(OWNER, holding(3, 64, 5, west, east)).allowed());
        // In east alone: the actor has no part there.
        Decision inEast = decide(OWNER, holding(15, 64, 5, west, east));
        Assertions.assertFalse(inEast.allowed());
        Assertions.assertEquals(Reason.NOT_INVOLVED_NOT_OWNER, inEast.reason());
    }

    @Test
    void regionsThatOnlyOverlapDoNotEncloseEachOther() {
        VaultRegion west = region("west", ACTOR, 0, 0, 0, 10, 100, 10);
        VaultRegion east = region("east", null, 8, 0, 0, 20, 100, 10);

        List<CaptureRules.RegionAt> at = CaptureRegions.at(ACTOR, OWNER, List.of(west, east));

        Assertions.assertTrue(at.stream().noneMatch(CaptureRules.RegionAt::encloses));
    }

    // Two regions on the same footprint, one above the other: a shop on y 0..9, a flat on y 10..20.

    @Test
    void aMemberOfTheRegionAboveHasNoSayOverTheRegionBelow() {
        VaultRegion shop = region("shop", null, 0, 0, 0, 10, 9, 10);
        VaultRegion flat = region("flat", ACTOR, 0, 10, 0, 10, 20, 10);

        Decision below = decide(OWNER, holding(5, 5, 5, shop, flat));
        Assertions.assertFalse(below.allowed());
        Assertions.assertEquals(Reason.NOT_INVOLVED_NOT_OWNER, below.reason());

        Assertions.assertTrue(decide(OWNER, holding(5, 15, 5, shop, flat)).allowed());
    }

    @Test
    void anOwnersPartInTheRegionBelowDoesNotProtectTheirContainerAbove() {
        VaultRegion shop = region("shop", OWNER, 0, 0, 0, 10, 9, 10);
        VaultRegion flat = region("flat", ACTOR, 0, 10, 0, 10, 20, 10);

        Assertions.assertTrue(decide(OWNER, holding(5, 15, 5, shop, flat)).allowed());
    }

    @Test
    void theBlocksWhereStackedRegionsMeetBelongToOneRegionEach() {
        VaultRegion shop = region("shop", null, 0, 0, 0, 10, 9, 10);
        VaultRegion flat = region("flat", ACTOR, 0, 10, 0, 10, 20, 10);

        Assertions.assertEquals(List.of(shop), holding(5, 9, 5, shop, flat));
        Assertions.assertEquals(List.of(flat), holding(5, 10, 5, shop, flat));
    }

    // A city with a plot inside it, and a flat inside the plot.

    @Test
    void aCityMemberIsNotAMemberOfAPlotInsideIt() {
        VaultRegion city = region("city", ACTOR, 0, 0, 0, 100, 100, 100);
        VaultRegion plot = region("plot", null, 10, 0, 10, 20, 100, 20);

        Decision decision = decide(OWNER, holding(15, 64, 15, city, plot));

        Assertions.assertFalse(decision.allowed());
    }

    @Test
    void aPlotMemberVaultsInThePlotThoughTheCityEnclosesIt() {
        VaultRegion city = region("city", null, 0, 0, 0, 100, 100, 100);
        VaultRegion plot = region("plot", ACTOR, 10, 0, 10, 20, 100, 20);

        Assertions.assertTrue(decide(OWNER, holding(15, 64, 15, city, plot)).allowed());
    }

    @Test
    void aPlotMemberIsNotAMemberOfAFlatInsideThePlot() {
        VaultRegion city = region("city", null, 0, 0, 0, 100, 100, 100);
        VaultRegion plot = region("plot", ACTOR, 10, 0, 10, 20, 100, 20);
        VaultRegion flat = region("flat", null, 12, 10, 12, 18, 20, 18);

        // In the flat, the plot encloses it, so the actor's part in the plot does not count.
        Assertions.assertFalse(decide(OWNER, holding(15, 15, 15, city, plot, flat)).allowed());
        // Under the flat, in the plot and the city only, it does.
        Assertions.assertTrue(decide(OWNER, holding(15, 5, 15, city, plot, flat)).allowed());
    }

    @Test
    void aRegionThatSharesAFaceWithTheOneAroundItIsStillNestedInIt() {
        VaultRegion plot = region("plot", ACTOR, 10, 0, 10, 20, 100, 20);
        VaultRegion shed = region("shed", null, 10, 0, 10, 14, 100, 14);

        List<CaptureRules.RegionAt> at = CaptureRegions.at(ACTOR, OWNER, List.of(plot, shed));

        Assertions.assertTrue(at.get(0).encloses());
        Assertions.assertFalse(at.get(1).encloses());
    }

    @Test
    void twoRegionsWithTheSameBoundsDoNotCancelEachOther() {
        VaultRegion first = region("first", ACTOR, 0, 0, 0, 10, 100, 10);
        VaultRegion second = region("second", null, 0, 0, 0, 10, 100, 10);

        Assertions.assertTrue(decide(OWNER, holding(5, 64, 5, first, second)).allowed());
    }
}
