package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a container held when a capture began: enough to store it as a vault, and to tell
 * afterwards whether it changed. Items carry the slots they occupied.
 */
public record ContainerSnapshot(@NotNull UUID worldUuid, @NotNull String worldName, int x, int y, int z,
                                @NotNull String material, @Nullable String blockData,
                                @NotNull List<VaultItem> items) {
}
