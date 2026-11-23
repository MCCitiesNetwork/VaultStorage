package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.database.StoredVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultItemEntity;
import com.minecraftcitiesnetwork.vaultstorage.vault.ItemSerialization;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/** The server's world. Main thread only. */
public final class BukkitPlacementWorld implements PlacementWorld {

    private final @NotNull Supplier<BoltService> bolt;

    public BukkitPlacementWorld(@NotNull Supplier<BoltService> bolt) {
        this.bolt = bolt;
    }

    /**
     * Places the block and fills it. A chest is placed as a single chest so it does not merge
     * with a neighbour. Each item goes back to the slot it was stored under when that slot
     * exists and is free; otherwise to the first free slot, and to the ground if there is none.
     */
    @Override
    public VaultPlacementService.@NotNull Result place(@NotNull StoredVault stored, @NotNull Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return new VaultPlacementService.Result(false, MessageKeys.PLACEMENT_WORLD_UNAVAILABLE);
        }
        Block target = world.getBlockAt(loc);
        if (target.getType() != Material.AIR) {
            return new VaultPlacementService.Result(false, MessageKeys.PLACEMENT_TARGET_NOT_EMPTY);
        }
        Material material = Material.matchMaterial(stored.vault().material());
        if (material == null || !material.isBlock()) {
            // Also covers an item-only material name stored by an old version.
            material = Material.CHEST;
        }
        target.setType(material, false);

        String blockData = stored.vault().blockData();
        if (blockData != null && !blockData.isBlank()) {
            try {
                BlockData data = Bukkit.createBlockData(blockData);
                if (data instanceof Chest chestData) {
                    chestData.setType(Chest.Type.SINGLE);
                }
                target.setBlockData(data, false);
            } catch (IllegalArgumentException ignored) {
                // Block data from an older game version: the block keeps its default state.
            }
        }

        if (!(target.getState() instanceof Container container)) {
            return new VaultPlacementService.Result(true, MessageKeys.PLACEMENT_BLOCK_PLACED);
        }
        Inventory inventory = container.getInventory();
        inventory.clear();
        for (VaultItemEntity row : stored.items()) {
            ItemStack stack = ItemSerialization.fromBytes(row.item());
            if (stack == null) {
                continue;
            }
            if (row.slot() >= 0 && row.slot() < inventory.getSize() && inventory.getItem(row.slot()) == null) {
                inventory.setItem(row.slot(), stack);
                continue;
            }
            for (ItemStack leftover : inventory.addItem(stack).values()) {
                world.dropItemNaturally(loc, leftover);
            }
        }
        return new VaultPlacementService.Result(true, MessageKeys.PLACEMENT_VAULT_PLACED);
    }

    @Override
    public void protect(@NotNull Location target, @NotNull UUID owner) {
        this.bolt.get().createProtection(target.getBlock(), owner);
    }
}
