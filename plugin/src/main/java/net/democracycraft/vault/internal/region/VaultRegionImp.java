package net.democracycraft.vault.internal.region;

import net.democracycraft.vault.api.region.VaultRegion;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public record VaultRegionImp(@NotNull String id,
                             @NotNull List<UUID> members,
                             @NotNull List<UUID> owners,
                             @NotNull BoundingBox boundingBox,
                             int priority) implements VaultRegion {

    @Override
    public boolean isMember(@NotNull UUID playerUuid) {
        return members.contains(playerUuid);
    }

    @Override
    public boolean isOwner(@NotNull UUID playerUuid) {
        return owners.contains(playerUuid);
    }

}
