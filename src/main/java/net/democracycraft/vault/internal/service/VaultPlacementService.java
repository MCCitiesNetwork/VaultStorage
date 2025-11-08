package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.convertible.Vault;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.internal.database.entity.VaultEntity;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Service responsible for placing a Vault back into the world.
 * Threading policy:
 * - World modifications MUST run on the main server thread.
 * - Use {@link #placeAtOriginalOnMainAsync(Vault, Consumer)} if invoking from async threads.
 */
public class VaultPlacementService {

    /**
     * Immutable result for placement operations.
     */
    public record Result(boolean success, String message) {}

    /**
     * Places the vault identified by its database id back into the world, reconstructing state from DB.
     * Runs DB access asynchronously and applies world changes on the main thread.
     * @param vaultUuid the vault id
     * @param callback result consumer executed on the main thread; may be null
     */
    public void placeFromDatabaseAsync(UUID vaultUuid, Consumer<Result> callback) {
        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable() {
            @Override public void run() {
                VaultService vs = plugin.getVaultService();
                var opt = vs.get(vaultUuid);
                if (opt.isEmpty()) {
                    new BukkitRunnable() { @Override public void run() { if (callback != null) callback.accept(new Result(false, "Vault not found.")); } }.runTask(plugin);
                    return;
                }
                VaultEntity e = opt.get();
                // Build location and material
                World world = Bukkit.getWorld(e.worldUuid);
                if (world == null) {
                    new BukkitRunnable() { @Override public void run() { if (callback != null) callback.accept(new Result(false, "World not available.")); } }.runTask(plugin);
                    return;
                }
                Material mat = null;
                if (e.material != null && !e.material.isBlank()) {
                    try { mat = Material.valueOf(e.material); } catch (IllegalArgumentException ignored) {}
                }
                if (mat == null) {
                    new BukkitRunnable() { @Override public void run() { if (callback != null) callback.accept(new Result(false, "Missing or invalid material.")); } }.runTask(plugin);
                    return;
                }
                Location loc = new Location(world, e.x, e.y, e.z);
                // Owner for protection recreation
                UUID ownerUuid = vs.getOwner(vaultUuid);
                // Load items
                List<VaultItemEntity> rows = vs.listItems(vaultUuid);
                List<ItemStack> items = new ArrayList<>(rows.size());
                int maxSlot = -1;
                for (VaultItemEntity row : rows) {
                    maxSlot = Math.max(maxSlot, row.slot);
                }
                int size = Math.max(0, maxSlot + 1);
                for (int i = 0; i < size; i++) items.add(null);
                for (VaultItemEntity row : rows) {
                    ItemStack it = ItemSerialization.fromBytes(row.item);
                    if (row.slot >= 0 && row.slot < items.size()) items.set(row.slot, it);
                }
                // Rebuild Vault and place on main
                Vault vault = new VaultImp(null, vaultUuid, items, mat, loc, e.createdAtEpochMillis == null ? Instant.now() : Instant.ofEpochMilli(e.createdAtEpochMillis), e.blockData);
                placeAtOriginalOnMainAsync(vault, result -> {

                    if (callback != null) callback.accept(result);

                    if (result.success()) {
                        // Create Bolt protection for the placed block with the original owner
                        BoltService bolt = plugin.getBoltService();
                        if (bolt != null && ownerUuid != null) {
                            Block placed = loc.getWorld().getBlockAt(loc);
                            try { bolt.createProtection(placed, ownerUuid); } catch (Throwable ignored) {}
                        }
                        // Remove the vault record after successful placement
                        new BukkitRunnable() { @Override public void run() {
                            try { vs.delete(vaultUuid); } catch (Exception ignored) {}
                        } }.runTaskAsynchronously(plugin);
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Places the vault's block at its original location and restores its contents.
     * Applies saved BlockData (orientation/state) when available.
     * Must be called on the main thread.
     */
    public Result placeAtOriginal(Vault vault) {
        Location loc = vault.blockLocation();
        Material mat = vault.blockMaterial();
        if (loc == null || mat == null) {
            return new Result(false, "Missing original location or material.");
        }
        World world = loc.getWorld();
        if (world == null) {
            return new Result(false, "World not available.");
        }
        Chunk chunk = world.getChunkAt(loc);
        if (!chunk.isLoaded()) chunk.load();
        Block target = world.getBlockAt(loc);
        if (target.getType() != Material.AIR && target.getType() != mat) {
            return new Result(false, "Target block not empty.");
        }
        target.setType(mat, true);

        // Restore BlockData for orientation/state if available
        String blockDataString = (vault instanceof VaultImp imp) ? imp.blockDataString() : null;
        if (blockDataString != null && !blockDataString.isBlank()) {
            try {
                BlockData data = Bukkit.createBlockData(blockDataString);
                target.setBlockData(data, true);
            } catch (IllegalArgumentException ignored) {
                // Silently ignore if data no longer valid for this server version/material
            }
        }

        if (target.getState() instanceof Container container) {
            Inventory inv = (container instanceof Chest chest) ? chest.getBlockInventory() : container.getInventory();
            inv.clear();
            List<ItemStack> items = vault.contents();
            int size = inv.getSize();
            int i = 0;
            for (ItemStack it : items) {
                if (it == null) continue;
                if (i >= size) { world.dropItemNaturally(loc, it.clone()); continue; }
                inv.setItem(i++, it.clone());
            }
            return new Result(true, "Vault placed.");
        } else {
            return new Result(true, "Block placed, but it has no inventory.");
        }
    }

    /**
     * Schedules {@link #placeAtOriginal(Vault)} on the main server thread and returns the result via callback.
     * Safe to call from any thread.
     * @param vault the vault to place
     * @param callback consumer of the result executed on the main thread; may be null
     */
    public void placeAtOriginalOnMainAsync(Vault vault, Consumer<Result> callback) {
        new BukkitRunnable() {
            @Override public void run() {
                Result res = placeAtOriginal(vault);
                if (callback != null) callback.accept(res);
            }
        }.runTask(VaultStoragePlugin.getInstance());
    }

    /**
     * Places the vault identified by its database id at a custom target location (relative placement),
     * reconstructing state from DB. World modifications happen on main thread; DB on async.
     * Deletes the vault record after success.
     * @param vaultUuid vault id
     * @param targetLoc location to place (must be in a loaded world)
     * @param callback result consumer (main thread), may be null
     */
    public void placeFromDatabaseRelativeAsync(UUID vaultUuid, Location targetLoc, Consumer<Result> callback) {
        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable() {
            @Override public void run() {
                VaultService vs = plugin.getVaultService();
                var opt = vs.get(vaultUuid);
                if (opt.isEmpty()) {
                    new BukkitRunnable(){@Override public void run(){ if (callback!=null) callback.accept(new Result(false, "Vault not found.")); }}.runTask(plugin);
                    return;
                }
                VaultEntity e = opt.get();
                World world = targetLoc.getWorld();
                if (world == null) {
                    new BukkitRunnable(){@Override public void run(){ if (callback!=null) callback.accept(new Result(false, "Target world not available.")); }}.runTask(plugin);
                    return;
                }
                Material mat = null;
                if (e.material != null && !e.material.isBlank()) {
                    try { mat = Material.valueOf(e.material); } catch (IllegalArgumentException ignored) {}
                }
                if (mat == null) {
                    Material fallback = Material.CHEST; // safe default
                    mat = fallback;
                }
                UUID ownerUuid = vs.getOwner(vaultUuid);
                List<VaultItemEntity> rows = vs.listItems(vaultUuid);
                List<ItemStack> contents = new ArrayList<>();
                int maxSlot = -1;
                for (VaultItemEntity row : rows) maxSlot = Math.max(maxSlot, row.slot);
                int size = Math.max(0, maxSlot + 1);
                for (int i=0;i<size;i++) contents.add(null);
                for (VaultItemEntity row : rows) {
                    ItemStack it = ItemSerialization.fromBytes(row.item);
                    if (row.slot >=0 && row.slot < contents.size()) contents.set(row.slot, it);
                }
                // Build ephemeral Vault for placement (location overridden)
                Material finalMat = mat;
                String blockDataString = e.blockData;
                List<ItemStack> finalContents = contents.stream().map(it -> it == null ? null : it.clone()).toList();
                new BukkitRunnable(){
                    @Override public void run() {
                        Result res = placeAt(finalMat, finalContents, targetLoc, blockDataString);
                        if (!res.success()) {
                            if (callback!=null) callback.accept(res);
                            return;
                        }
                        // Recreate Bolt protection with original owner if present
                        BoltService bolt = plugin.getBoltService();
                        if (bolt != null && ownerUuid != null) {
                            Block placed = targetLoc.getBlock();
                            try { bolt.createProtection(placed, ownerUuid); } catch (Throwable ignored) {}
                        }
                        // Delete vault record then callback on main
                        new BukkitRunnable(){
                            @Override public void run(){
                                try { vs.delete(vaultUuid);} catch(Throwable ignored){}
                                new BukkitRunnable(){ @Override public void run(){ if (callback!=null) callback.accept(res); } }.runTask(plugin);
                            }
                        }.runTaskAsynchronously(plugin);
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Core placement logic at arbitrary location with given material, contents and blockData.
     */
    private Result placeAt(Material mat, List<ItemStack> contents, Location loc, String blockDataString) {
        if (mat == null) return new Result(false, "Material missing.");
        if (loc == null || loc.getWorld() == null) return new Result(false, "Invalid location.");
        World world = loc.getWorld();
        Chunk chunk = world.getChunkAt(loc);
        if (!chunk.isLoaded()) chunk.load();
        Block target = world.getBlockAt(loc);
        if (target.getType() != Material.AIR && target.getType() != mat) {
            return new Result(false, "Target not empty.");
        }
        target.setType(mat, true);
        if (blockDataString != null && !blockDataString.isBlank()) {
            try { BlockData data = Bukkit.createBlockData(blockDataString); target.setBlockData(data, true); } catch (IllegalArgumentException ignored) {}
        }
        if (target.getState() instanceof Container container) {
            Inventory inv = container.getInventory();
            inv.clear();
            int size = inv.getSize();
            int i=0;
            for (ItemStack it : contents) {
                if (it == null) continue;
                if (i >= size) { world.dropItemNaturally(loc, it.clone()); continue; }
                inv.setItem(i++, it.clone());
            }
            return new Result(true, "Vault placed.");
        }
        return new Result(true, "Block placed.");
    }
}
