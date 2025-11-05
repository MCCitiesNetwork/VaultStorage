package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.internal.mappable.VaultImp;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Service responsible for capturing a container block into a Vault model.
 * Threading policy:
 * - Must run on the main server thread because it interacts with the world (block state and inventories).
 * - Use {@link #captureOnMainAsync(Player, Block, Consumer)} if calling from async threads.
 */
public class VaultCaptureService {

    /**
     * Captures the given block's inventory and metadata into a new Vault.
     * Contract:
     * - The block must be a Container; otherwise throws IllegalArgumentException.
     * - Clears the block's inventory and removes the block (sets to AIR).
     * - Returns a new VaultImp with items, material, location, timestamp, and block data string.
     *
     * This method must be called on the main thread.
     */
    public VaultImp captureFromBlock(Player actor, Block block) {
        if (!(block.getState() instanceof Container container)) {
            throw new IllegalArgumentException("Target block is not a container.");
        }
        Inventory captureInv = (container instanceof Chest chest) ? chest.getBlockInventory() : container.getInventory();
        List<ItemStack> stacks = Arrays.stream(captureInv.getContents())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // Clear inventory and remove block
        captureInv.clear();
        Material material = block.getType();
        var location = block.getLocation();
        Instant when = Instant.now();
        block.getBlockData();
        String blockDataString = block.getBlockData().getAsString();
        block.setType(Material.AIR);

        UUID vaultId = UUID.randomUUID();
        UUID owner = actor.getUniqueId();
        return new VaultImp(owner, vaultId, stacks, material, location, when, blockDataString);
    }

    /**
     * Schedules a capture on the main thread and delivers the result to the callback.
     * Safe to call from any thread.
     * @param actor player performing the capture
     * @param block target block (must be a Container)
     * @param callback consumer receiving the resulting VaultImp (on main thread)
     */
    public void captureOnMainAsync(Player actor, Block block, Consumer<VaultImp> callback) {
        new BukkitRunnable() {
            @Override public void run() {
                VaultImp vault = captureFromBlock(actor, block);
                if (callback != null) callback.accept(vault);
            }
        }.runTask(VaultStoragePlugin.getInstance());
    }
}
