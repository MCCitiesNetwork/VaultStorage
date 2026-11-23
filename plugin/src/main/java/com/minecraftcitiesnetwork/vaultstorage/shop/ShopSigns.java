package com.minecraftcitiesnetwork.vaultstorage.shop;

import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The signs a shop plugin keeps on containers. The host names no shop plugin: a module adds
 * one of these for the plugin it knows, and with none added there are simply no shop signs.
 */
public interface ShopSigns {

    /** Whether the block is a shop's sign. */
    boolean isShopSign(@Nullable Block block);

    /** The shop signs on the container; empty when it has none. */
    @NotNull List<Block> findShopSigns(@Nullable Block containerBlock);

    /**
     * Removes a shop sign the way its plugin would, so the plugin's own records follow. Main
     * thread.
     *
     * @param actor who is removing it, or null for an automated capture
     * @return true if the block was a shop sign and was removed
     */
    boolean removeShopSign(@Nullable Player actor, @Nullable Block signBlock);
}
