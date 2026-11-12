package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.database.ClaimResult;
import com.minecraftcitiesnetwork.vaultstorage.database.StoredVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultItemEntity;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultRepository;
import com.minecraftcitiesnetwork.vaultstorage.vault.ItemSerialization;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
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
import org.jetbrains.annotations.Nullable;

/**
 * Places a vault back into the world.
 *
 * <ol>
 *   <li>Database: claim the vault. Of two actors, exactly one gets it.</li>
 *   <li>Main thread: place the block, fill it, protect it.</li>
 *   <li>Database: delete the vault if the block went in, or release the claim if it did not.</li>
 * </ol>
 *
 * <p>The vault is never deleted before its block exists, and a claimed vault cannot be placed a
 * second time, so a failure at any step leaves the items in at least one place and never two.
 */
public final class VaultPlacementService {

    private final @NotNull VaultStoragePlugin plugin;

    public VaultPlacementService(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
    }

    /** Immutable result for placement operations. */
    /** {@code messageKey} names the line in messages.yml that tells the player what happened. */
    public record Result(boolean success, @NotNull String messageKey) {}

    /**
     * Places the vault at the target. The callback runs on the main thread.
     *
     * @param vaultUuid vault id
     * @param targetLoc location to place (must be in a loaded world)
     * @param callback result consumer (main thread), may be null
     */
    public void placeFromDatabaseRelativeAsync(@NotNull UUID vaultUuid, @NotNull Location targetLoc,
                                               @Nullable Consumer<Result> callback) {
        VaultRepository repository = plugin.repository();
        Executor database = plugin.databaseExecutor();
        Executor mainThread = plugin.getServer().getScheduler().getMainThreadExecutor(plugin);
        Consumer<Result> reply = callback == null ? result -> { } : callback;

        CompletableFuture.supplyAsync(() -> repository.claim(vaultUuid), database)
                .thenComposeAsync(claim -> switch (claim) {
                    case ClaimResult.NoSuchVault missing ->
                            CompletableFuture.completedFuture(new Result(false, MessageKeys.INVENTORY_NOT_FOUND));
                    case ClaimResult.AlreadyClaimed held ->
                            CompletableFuture.completedFuture(new Result(false, MessageKeys.INVENTORY_IN_USE));
                    case ClaimResult.Claimed claimed -> placeClaimed(plugin, claimed.vault(), targetLoc, database);
                }, mainThread)
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Placing vault " + vaultUuid + " failed.", failure);
                    return new Result(false, MessageKeys.PLACEMENT_FAILED);
                })
                .thenAcceptAsync(reply, mainThread);
    }

    /** Main thread. The vault is claimed; it is deleted or released before this future completes. */
    private @NotNull CompletableFuture<Result> placeClaimed(@NotNull VaultStoragePlugin plugin, @NotNull StoredVault stored,
                                                           @NotNull Location targetLoc, @NotNull Executor database) {
        UUID vaultUuid = stored.vault().uuid();
        Result placed;
        try {
            placed = placeAt(stored, targetLoc);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Placing vault " + vaultUuid + " failed; it stays in storage.", ex);
            placed = new Result(false, MessageKeys.PLACEMENT_FAILED);
        }
        if (!placed.success()) {
            Result refused = placed;
            return CompletableFuture.supplyAsync(() -> {
                plugin.repository().release(vaultUuid);
                return refused;
            }, database);
        }
        BoltService bolt = plugin.bolt();
        if (bolt != null) {
            try {
                bolt.createProtection(targetLoc.getBlock(), stored.vault().ownerUuid());
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Vault " + vaultUuid
                        + " was placed but could not be locked for its owner.", ex);
            }
        }
        Result done = placed;
        return CompletableFuture.supplyAsync(() -> {
            try {
                plugin.repository().delete(vaultUuid);
            } catch (RuntimeException ex) {
                // The block is in the world. The vault stays claimed, so it cannot be placed again.
                plugin.getLogger().log(Level.SEVERE, "Vault " + vaultUuid + " was placed at "
                        + targetLoc.getWorld().getName() + " " + targetLoc.getBlockX() + "," + targetLoc.getBlockY()
                        + "," + targetLoc.getBlockZ() + " but could not be deleted. Delete it by hand.", ex);
            }
            return done;
        }, database);
    }

    /**
     * Places the block and fills it. A chest is placed as a single chest so it does not merge
     * with a neighbour. Each item goes back to the slot it was stored under when that slot
     * exists and is free; otherwise to the first free slot, and to the ground if there is none.
     */
    private @NotNull Result placeAt(@NotNull StoredVault stored, @NotNull Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return new Result(false, MessageKeys.PLACEMENT_WORLD_UNAVAILABLE);
        }
        Block target = world.getBlockAt(loc);
        if (target.getType() != Material.AIR) {
            return new Result(false, MessageKeys.PLACEMENT_TARGET_NOT_EMPTY);
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
            return new Result(true, MessageKeys.PLACEMENT_BLOCK_PLACED);
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
        return new Result(true, MessageKeys.PLACEMENT_VAULT_PLACED);
    }
}
