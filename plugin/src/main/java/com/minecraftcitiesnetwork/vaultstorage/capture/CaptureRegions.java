package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.region.RegionGeometry;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Turns the regions at a container into what {@link CaptureRules} decides on. */
public final class CaptureRegions {

    private CaptureRegions() {
    }

    /**
     * @param regions the regions the container is in, by their real shape. Regions that only
     *                overlap elsewhere, or lie above or below it, are not among them.
     * @param owner   the container's owner, or null when it has none
     */
    public static @NotNull List<CaptureRules.RegionAt> at(@NotNull UUID actor, @Nullable UUID owner,
                                                          @NotNull List<VaultRegion> regions) {
        List<CaptureRules.RegionAt> result = new ArrayList<>(regions.size());
        for (VaultRegion region : regions) {
            // "Nested inside" is decided by bounding box.
            boolean encloses = regions.stream().anyMatch(other -> other != region
                    && RegionGeometry.nestedInside(region.boundingBox(), other.boundingBox()));
            result.add(new CaptureRules.RegionAt(region.id(), region.isPartOfRegion(actor),
                    owner != null && region.isPartOfRegion(owner), encloses));
        }
        return result;
    }
}
