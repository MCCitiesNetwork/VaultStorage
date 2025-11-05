package net.democracycraft.vault.api.region;

import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public interface VaultRegion {

    /**
     * Stable WorldGuard region identifier within a world.
     * @return non-null region id
     */
    @NotNull String id();

    @NotNull List<UUID> members();

    @NotNull List<UUID> owners();

    @NotNull BoundingBox boundingBox();

    boolean isMember(@NotNull UUID playerUuid);

    boolean isOwner(@NotNull UUID playerUuid);
}
