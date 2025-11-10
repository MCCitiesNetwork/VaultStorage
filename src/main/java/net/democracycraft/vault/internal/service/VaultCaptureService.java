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
     * Determines if the given block is a container and if its effective inventory is empty.
     * <p>For chests, uses the combined {@link Chest#getBlockInventory()} ensuring double chests are evaluated as a whole.</p>
     * <p>Empty definition: no non-null ItemStack whose type != AIR and amount > 0.</p>
     * @param block target block expected to be a Container
     * @return true if empty, false otherwise
     * @throws IllegalArgumentException if the block is not a container
     */
    public boolean isContainerEmpty(Block block) {
        if (!(block.getState() instanceof Container c)) {
            throw new IllegalArgumentException("Target block is not a container.");
        }
        Inventory inv = (c instanceof Chest chest) ? chest.getBlockInventory() : c.getInventory();
        for (ItemStack stack : inv.getContents()) {
            if (stack == null) continue;
            if (stack.getType() == Material.AIR) continue;
            if (stack.getAmount() <= 0) continue;
            return false; // Found meaningful content
        }
        return true;
    }

    /**
     * Captures the given block's inventory and metadata into a new Vault.
     * Contract:
     * - The block must be a Container; otherwise throws IllegalArgumentException.
     * - Clears the block's inventory and removes the block (sets to AIR).
     * - Returns a new {@link VaultImp} with items, material, location, timestamp, and block data string.
     * - Does NOT perform an emptiness check; callers should invoke {@link #isContainerEmpty(Block)} beforehand when conditional behavior is required.
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
                .filter(is -> is.getType() != Material.AIR && is.getAmount() > 0)
                .collect(Collectors.toList());

        // Clear inventory and remove block
        captureInv.clear();
        Material material = block.getType();
        var location = block.getLocation();
        Instant when = Instant.now();
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
