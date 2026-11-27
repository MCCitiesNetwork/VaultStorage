package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Questions about a block that a capture asks before it touches anything. Main thread only. */
public final class ContainerBlocks {

    private static final BlockFace[] ADJACENT_FACES = new BlockFace[]{
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private ContainerBlocks() {
    }

    /**
     * Determines if the given block is a container and if its effective inventory is empty.
     * For non-container blocks returns true (treated as empty capture behavior).
     * <p>For chests, uses the combined {@link Chest#getBlockInventory()} ensuring double chests are evaluated as a whole.</p>
     * <p>Empty definition: no non-null ItemStack whose type != AIR and amount > 0.</p>
     * @param block target block
     * @return true if empty (or not a container), false otherwise
     */
    public static boolean isEmpty(@NotNull Block block) {
        if (!(block.getState() instanceof Container c)) {
            // Non-container: treat as empty so capture logic will produce no vault
            return true;
        }
        // Note: checking getBlockInventory() on a Double Chest returns ONLY the half belonging to this block,
        // which is exactly what we need for split-logic.
        Inventory inv = (c instanceof Chest chest) ? chest.getBlockInventory() : c.getInventory();
        for (ItemStack stack : inv.getContents()) {
            if (stack != null && !stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static @NotNull List<Block> otherHalves(@NotNull Block block, @NotNull Container container) {
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

    public static boolean isSign(@NotNull Block block) {
        return block.getState() instanceof Sign;
    }

    public static @Nullable Block attachedContainer(@NotNull Block signBlock) {
        if (!isSign(signBlock)) return null;

        if (signBlock.getBlockData() instanceof WallSign wallSign) {
            Block attached = signBlock.getRelative(wallSign.getFacing().getOppositeFace());
            if (attached.getState() instanceof Container) {
                return attached;
            }
        }

        // Conservative fallback for non-wall signs: check immediate neighbors.
        for (BlockFace face : ADJACENT_FACES) {
            Block neighbor = signBlock.getRelative(face);
            if (neighbor.getState() instanceof Container) {
                return neighbor;
            }
        }
        return null;
    }

    /** Where a block is, for a log line: {@code world x,y,z}. */
    public static @NotNull String describe(@NotNull Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + "," + block.getZ();
    }
}
