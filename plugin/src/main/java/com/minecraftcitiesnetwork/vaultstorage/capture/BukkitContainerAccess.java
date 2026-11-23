package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import com.minecraftcitiesnetwork.vaultstorage.vault.ItemSerialization;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Captures one container block. Built per capture, because removing the block also moves its
 * Bolt protection: the block's own protection goes, and the other half of a double chest is
 * protected again for the same owner.
 */
public final class BukkitContainerAccess implements ContainerAccess<Block> {

    private final @Nullable BoltService bolt;
    private final @NotNull UUID protectionOwner;
    private final @NotNull List<Block> otherHalves;
    private final @NotNull Logger logger;

    public BukkitContainerAccess(@Nullable BoltService bolt, @NotNull UUID protectionOwner,
                                 @NotNull List<Block> otherHalves, @NotNull Logger logger) {
        this.bolt = bolt;
        this.protectionOwner = protectionOwner;
        this.otherHalves = List.copyOf(otherHalves);
        this.logger = logger;
    }

    /** A chest's own half, not the combined inventory of a double chest. */
    private static @NotNull Inventory inventoryOf(@NotNull Container container) {
        return container instanceof Chest chest ? chest.getBlockInventory() : container.getInventory();
    }

    private static @NotNull List<VaultItem> itemsOf(@NotNull Block block) {
        if (!(block.getState() instanceof Container container)) {
            throw new IllegalArgumentException("Target block is not a container.");
        }
        return ItemSerialization.toVaultItems(inventoryOf(container).getContents());
    }

    @Override
    public @NotNull ContainerSnapshot snapshot(@NotNull Block block) {
        return new ContainerSnapshot(block.getWorld().getUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), block.getType().name(),
                block.getBlockData().getAsString(), itemsOf(block));
    }

    @Override
    public boolean stillMatches(@NotNull Block block, @NotNull ContainerSnapshot snapshot) {
        if (!block.getType().name().equals(snapshot.material()) || !(block.getState() instanceof Container)) {
            return false;
        }
        return snapshot.holds(itemsOf(block));
    }

    @Override
    public void remove(@NotNull Block block) {
        if (this.bolt != null) {
            try {
                this.bolt.removeProtection(block);
                for (Block half : this.otherHalves) {
                    this.bolt.createProtection(half, this.protectionOwner);
                }
            } catch (RuntimeException ex) {
                // The items are safely stored; a protection left behind is the lesser problem.
                this.logger.log(Level.WARNING, "Could not move the Bolt protection at "
                        + ContainerBlocks.describe(block), ex);
            }
        }
        if (block.getState() instanceof Container container) {
            inventoryOf(container).clear();
        }
        block.setType(Material.AIR);
    }
}
