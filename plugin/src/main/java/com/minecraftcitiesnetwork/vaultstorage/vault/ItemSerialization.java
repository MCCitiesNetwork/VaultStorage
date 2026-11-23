package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import org.jetbrains.annotations.Nullable;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Utility methods to serialize and deserialize ItemStacks to and from byte arrays.
 */
public final class ItemSerialization {
    private ItemSerialization() {}

    /** The stack as it is stored, for this slot. */
    public static @NotNull VaultItem toVaultItem(int slot, @NotNull ItemStack stack) {
        return new VaultItem(slot, stack.getAmount(), toBytes(stack));
    }

    /**
     * Serialize an ItemStack to bytes.
     * @return bytes array (never null)
     */
    public static byte @NotNull [] toBytes(@NotNull ItemStack stack) {
        return stack.serializeAsBytes();
    }

    /**
     * Deserialize an ItemStack from bytes.
     * @param data bytes array (may be null)
     * @return ItemStack or null
     */
    public static @Nullable ItemStack fromBytes(byte @Nullable [] data) {
        if (data == null || data.length == 0) return null;
        return ItemStack.deserializeBytes(data);
    }
}

