package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import com.minecraftcitiesnetwork.vaultstorage.vault.ItemSerialization;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.entity.Hanging;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Captures an item frame or a painting: the entity as an item, and what a frame shows. Removing
 * the entity disposes of its Bolt protection.
 */
public final class HangingAccess implements ContainerAccess<Hanging> {

    /** The entity and what it shows, in the order they are stored. A slot is an offset from the first free one. */
    private static @NotNull List<VaultItem> itemsOf(@NotNull Hanging hanging) {
        List<ItemStack> stacks = HangingVaultSupport.itemStacksFrom(hanging);
        List<VaultItem> items = new ArrayList<>(stacks.size());
        for (int offset = 0; offset < stacks.size(); offset++) {
            items.add(ItemSerialization.toVaultItem(offset, stacks.get(offset)));
        }
        return items;
    }

    /** Anchored at the block the entity hangs on. */
    @Override
    public @NotNull ContainerSnapshot snapshot(@NotNull Hanging hanging) {
        Block supporting = HangingVaultSupport.resolveSupportingBlock(hanging);
        return new ContainerSnapshot(supporting.getWorld().getUID(), supporting.getWorld().getName(),
                supporting.getX(), supporting.getY(), supporting.getZ(), hanging.getType().name(), null,
                itemsOf(hanging));
    }

    @Override
    public boolean stillMatches(@NotNull Hanging hanging, @NotNull ContainerSnapshot snapshot) {
        return hanging.isValid() && snapshot.holds(itemsOf(hanging));
    }

    @Override
    public void remove(@NotNull Hanging hanging) {
        hanging.remove();
    }
}
