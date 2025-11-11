package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.internal.mappable.VaultImp;
import org.bukkit.Material;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Service responsible for capturing a block into a Vault model.
 * <p>Behavior expansion: non-container blocks can now be "vaulted". They behave identically to an empty container capture:
 * Bolt protection is removed, no vault is created, the block remains in place.</p>
 * Threading policy:
 * - Must run on the main server thread because it interacts with the world (block state and inventories).
 * - Use {@link #captureOnMainAsync(Player, Block, Consumer)} if calling from async threads.
 */
public class VaultCaptureService {

    /**
     * Determines if the given block is a container and if its effective inventory is empty.
     * For non-container blocks returns true (treated as empty capture behavior).
     * <p>For chests, uses the combined {@link Chest#getBlockInventory()} ensuring double chests are evaluated as a whole.</p>
     * <p>Empty definition: no non-null ItemStack whose type != AIR and amount > 0.</p>
     * @param block target block
     * @return true if empty (or not a container), false otherwise
     */
    public boolean isContainerEmpty(Block block) {
        if (!(block.getState() instanceof Container c)) {
            // Non-container: treat as empty so capture logic will produce no vault
            return true;
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
     * Captures the given container block's inventory and metadata into a new Vault.
     * Contract:
     * - The block must be a Container; otherwise throws IllegalArgumentException. (Non-container blocks never reach this path.)
     * - Clears the block's inventory and removes the block (sets to AIR).
     * - Returns a new {@link VaultImp} with items, material, location, timestamp, and block data string.
     * - Does NOT perform an emptiness check; callers should invoke {@link #isContainerEmpty(Block)} beforehand when conditional behavior is required.
     * <p>This method must be called on the main thread.</p>
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
     * Schedules a container capture on the main thread and delivers the result to the callback.
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

    /**
     * Immutable outcome produced by {@link #captureWithDoubleChestSupport(Player, Block, UUID, boolean)}.
     * Contract:
     * <ul>
     *   <li>If {@code empty} is true: no block removed (original block remains), no vault created, bolt protection removed, other halves not re-protected.</li>
     *   <li>If {@code empty} is false (container with contents): block removed & vaulted, remaining halves (if any) re-protected under {@code finalOwner}.</li>
     * </ul>
     * For non-container blocks {@code empty} will always be true.
     */
    public record CaptureOutcome(boolean empty, VaultImp vault, UUID originalOwner, UUID finalOwner, List<Block> reProtectedHalves) {}

    /**
     * Captures a block into a vault while preserving protection rules.
     * <p>Behavior:</p>
     * <ul>
     *   <li>Non-container blocks: treated as empty capture (protection removed, block kept, no vault).</li>
     *   <li>Container blocks: if inventory empty -> same as non-container (no vault). If non-empty -> items persisted, block removed.</li>
     *   <li>Double chests: remaining half(s) re-protected when vault created.</li>
     * </ul>
     * Ownership transfer: final owner is originalOwner if present else actor.
     */
    public CaptureOutcome captureWithDoubleChestSupport(Player actor, Block block, UUID originalOwner, boolean hasOverride) {
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        UUID finalOwner = originalOwner != null ? originalOwner : actor.getUniqueId();

        // Non-container path: treat as empty
        if (!(block.getState() instanceof Container container)) {
            if (bolt != null) {
                try { bolt.removeProtection(block); } catch (Throwable ignored) {}
            }
            return new CaptureOutcome(true, null, originalOwner, finalOwner, List.of());
        }

        List<Block> otherHalves = getHalves(block, container);

        boolean empty = isContainerEmpty(block);

        // Always remove protection from clicked block
        if (bolt != null) {
            try { bolt.removeProtection(block); } catch (Throwable ignored) {}
        }

        if (empty) {
            return new CaptureOutcome(true, null, originalOwner, finalOwner, List.of());
        }

        // Capture block contents into vault (removes block)
        VaultImp vault = captureFromBlock(actor, block);

        // Re-protect other half(s) after removal
        List<Block> reProtected = new ArrayList<>();
        if (bolt != null && !otherHalves.isEmpty()) {
            for (Block half : otherHalves) {
                try {
                    bolt.createProtection(half, finalOwner);
                    reProtected.add(half);
                } catch (Throwable ignored) {}
            }
        }

        return new CaptureOutcome(false, vault, originalOwner, finalOwner, List.copyOf(reProtected));
    }

    private static @NotNull List<Block> getHalves(Block block, Container container) {
        List<Block> otherHalves = new ArrayList<>();
        if (container instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                InventoryHolder leftHolder = dc.getLeftSide();
                InventoryHolder rightHolder = dc.getRightSide();
                Chest left = (leftHolder instanceof Chest l) ? l : null;
                Chest right = (rightHolder instanceof Chest r) ? r : null;
                if (left != null && right != null) {
                    Block leftBlock = left.getBlock();
                    Block rightBlock = right.getBlock();
                    if (block.equals(leftBlock)) {
                        otherHalves.add(rightBlock);
                    } else if (block.equals(rightBlock)) {
                        otherHalves.add(leftBlock);
                    }
                }
            }
        }
        return otherHalves;
    }

}
