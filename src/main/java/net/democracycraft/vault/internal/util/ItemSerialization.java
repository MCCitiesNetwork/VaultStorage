package net.democracycraft.vault.internal.util;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.io.*;

/**
 * Utility methods to serialize and deserialize ItemStacks to and from byte arrays.
 */
public final class ItemSerialization {
    private ItemSerialization() {}

    /**
     * Serialize an ItemStack to bytes.
     * @return bytes array (never null)
     */
    public static byte[] toBytes(@NotNull ItemStack stack) {
        return stack.serializeAsBytes();
    }

    /**
     * Deserialize an ItemStack from bytes.
     * @param data bytes array (may be null)
     * @return ItemStack or null
     */
    public static ItemStack fromBytes(byte[] data) {
        if (data == null || data.length == 0) return null;
        return ItemStack.deserializeBytes(data);
    }
}

