package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a container or a hanging entity held when a capture began: enough to store it as a vault, and to tell
 * afterwards whether it changed. Items carry the slots they occupied.
 */
public record ContainerSnapshot(@NotNull UUID worldUuid, @NotNull String worldName, int x, int y, int z,
                                @NotNull String material, @Nullable String blockData,
                                @NotNull List<VaultItem> items) {

    /** Whether these are exactly the items read: the same stacks in the same slots. */
    public boolean holds(@NotNull List<VaultItem> now) {
        if (now.size() != this.items.size()) {
            return false;
        }
        for (int index = 0; index < now.size(); index++) {
            VaultItem current = now.get(index);
            VaultItem read = this.items.get(index);
            if (current.slot() != read.slot() || current.amount() != read.amount()
                    || !Arrays.equals(current.item(), read.item())) {
                return false;
            }
        }
        return true;
    }
}
