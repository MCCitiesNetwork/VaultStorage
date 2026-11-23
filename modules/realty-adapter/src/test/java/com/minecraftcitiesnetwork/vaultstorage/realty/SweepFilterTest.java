package com.minecraftcitiesnetwork.vaultstorage.realty;

import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegionImp;
import java.util.List;
import java.util.UUID;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SweepFilterTest {

    private static final UUID BUYER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID PREVIOUS = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID NEIGHBOUR = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    /** The swept plot's bounding box is 0..20 on x and z. */
    private static final VaultRegion PLOT = new VaultRegionImp("plot", List.of(MEMBER), List.of(),
            new BoundingBox(0, 0, 0, 20, 100, 20), 0);
    private static final VaultRegion CELLAR = new VaultRegionImp("cellar", List.of(), List.of(),
            new BoundingBox(2, 0, 2, 6, 10, 6), 0);
    private static final VaultRegion STREET = new VaultRegionImp("street", List.of(NEIGHBOUR), List.of(),
            new BoundingBox(15, 0, -10, 40, 100, 40), 0);

    /**
     * The plot is L-shaped: it has the whole strip x 0..9 and, of the strip x 10..20, only z 0..9.
     * The corner x 10..20, z 10..20 is inside its bounding box and not inside the plot.
     */
    private static List<VaultRegion> regionsAt(int x, int y, int z) {
        boolean inPlot = x >= 0 && x <= 20 && z >= 0 && z <= 20 && !(x >= 10 && z >= 10);
        boolean inCellar = x >= 2 && x <= 6 && y <= 10 && z >= 2 && z <= 6;
        boolean inStreet = x >= 15 && x <= 40 && z >= -10 && z <= 40;
        return java.util.stream.Stream.of(inPlot ? PLOT : null, inCellar ? CELLAR : null, inStreet ? STREET : null)
                .filter(java.util.Objects::nonNull).toList();
    }

    private final SweepFilter filter = SweepFilter.forSweep(PLOT, BUYER, SweepFilterTest::regionsAt);

    @Test
    void thePreviousOccupantsContainerInThePlotIsVaulted() {
        Assertions.assertTrue(this.filter.shouldVault(PREVIOUS, 8, 64, 8));
    }

    @Test
    void theBuyersAndAMembersContainersStay() {
        Assertions.assertFalse(this.filter.shouldVault(BUYER, 8, 64, 8));
        Assertions.assertFalse(this.filter.shouldVault(MEMBER, 8, 64, 8));
    }

    @Test
    void aContainerWithNoOwnerStays() {
        Assertions.assertFalse(this.filter.shouldVault(null, 8, 64, 8));
    }

    @Test
    void aContainerInsideTheBoundingBoxButOutsideThePlotsShapeStays() {
        // The corner the L leaves out belongs to someone else, whatever the box says.
        Assertions.assertFalse(this.filter.shouldVault(PREVIOUS, 15, 64, 15));
        Assertions.assertFalse(this.filter.shouldVault(NEIGHBOUR, 12, 64, 12));
    }

    @Test
    void aContainerOutsideTheBoundingBoxStays() {
        Assertions.assertFalse(this.filter.shouldVault(PREVIOUS, 30, 64, 5));
    }

    @Test
    void aContainerInARegionNestedInsideThePlotStays() {
        Assertions.assertFalse(this.filter.shouldVault(PREVIOUS, 4, 5, 4));
        // Above the cellar, in the plot alone, it is vaulted.
        Assertions.assertTrue(this.filter.shouldVault(PREVIOUS, 4, 64, 4));
    }

    @Test
    void anOwnerWhoHasAPartInAnotherRegionThereKeepsTheirContainer() {
        // x 15..20, z 0..9 is in the plot and in the street; the neighbour is a member of the street.
        Assertions.assertFalse(this.filter.shouldVault(NEIGHBOUR, 17, 64, 5));
        // The previous occupant has no part in the street, so theirs still goes.
        Assertions.assertTrue(this.filter.shouldVault(PREVIOUS, 17, 64, 5));
    }

    @Test
    void anOwnerWithNoPartInAnOverlappingRegionIsStillVaulted() {
        // x 17, z 5 is in the plot and in the street; the previous occupant has no part in the street.
        Assertions.assertTrue(this.filter.shouldVault(PREVIOUS, 17, 64, 5));
    }

    @Test
    void aContainerInARegionAboveOrBelowThePlotStays() {
        VaultRegion ground = new VaultRegionImp("ground", List.of(), List.of(), new BoundingBox(0, 0, 0, 20, 9, 20), 0);
        VaultRegion upstairs = new VaultRegionImp("upstairs", List.of(), List.of(), new BoundingBox(0, 10, 0, 20, 20, 20), 0);
        SweepFilter upstairsSold = SweepFilter.forSweep(upstairs, BUYER,
                (x, y, z) -> List.of(y >= 10 ? upstairs : ground));

        Assertions.assertTrue(upstairsSold.shouldVault(PREVIOUS, 8, 15, 8));
        Assertions.assertFalse(upstairsSold.shouldVault(PREVIOUS, 8, 9, 8));
        Assertions.assertFalse(upstairsSold.shouldVault(PREVIOUS, 8, 5, 8));
    }

    @Test
    void aRegionWithTheSameBoundsAsThePlotIsNotNestedInIt() {
        VaultRegion twin = new VaultRegionImp("twin", List.of(NEIGHBOUR), List.of(),
                new BoundingBox(0, 0, 0, 20, 100, 20), 0);
        SweepFilter twinned = SweepFilter.forSweep(PLOT, BUYER, (x, y, z) -> List.of(PLOT, twin));

        Assertions.assertTrue(twinned.shouldVault(PREVIOUS, 8, 64, 8));
        // The twin's own member keeps theirs.
        Assertions.assertFalse(twinned.shouldVault(NEIGHBOUR, 8, 64, 8));
    }

    @Test
    void anEntitysPositionIsTheBlockItIsIn() {
        Assertions.assertTrue(this.filter.shouldVault(PREVIOUS, 9.99, 64.5, 9.99));
        Assertions.assertFalse(this.filter.shouldVault(PREVIOUS, 10.01, 64.5, 10.01));
    }
}
