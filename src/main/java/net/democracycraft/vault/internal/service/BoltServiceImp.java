package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.popcraft.bolt.BoltAPI;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.*;

/**
 * Service implementation that integrates with Bolt to query and manage block protections.
 * <p>
 * This implementation ensures that protections are consistently applied across both halves
 * of a double chest, maintaining ownership integrity.
 */
public class BoltServiceImp implements BoltService {

    private final BoltAPI api;
    private final VaultStoragePlugin plugin;

    /**
     * Constructs a new BoltService implementation that integrates with the Bolt plugin.
     *
     * @param plugin the VaultStorage plugin instance (not null)
     * @throws IllegalStateException if the BoltAPI is not found or not registered
     */
    public BoltServiceImp(@NotNull VaultStoragePlugin plugin) {
        this.plugin = plugin;
        BoltAPI loaded = this.plugin.getServer().getServicesManager().load(BoltAPI.class);
        if (loaded == null) {
            throw new IllegalStateException("BoltAPI service not found. Ensure the Bolt plugin is installed and registered.");
        }
        this.api = loaded;
    }

    /**
     * Returns the underlying BoltAPI instance.
     *
     * @return the BoltAPI instance (never null)
     */
    public @NotNull BoltAPI getApi() {
        return api;
    }

    /**
     * Gets the owner of the given block's protection, if one exists.
     *
     * @param block the block to check (not null)
     * @return the owner's UUID, or null if the block is not protected
     */
    @Override
    public @Nullable UUID getOwner(@NotNull Block block) {
        Objects.requireNonNull(block, "block");
        Protection protection = api.findProtection(block);
        return protection != null ? protection.getOwner() : null;
    }

    /**
     * Checks whether the given player is the owner of the block's protection.
     *
     * @param playerUUID the player's UUID (not null)
     * @param block      the block to check (not null)
     * @return true if the player owns the block's protection; false otherwise
     */
    @Override
    public boolean isOwner(@NotNull UUID playerUUID, @NotNull Block block) {
        Objects.requireNonNull(playerUUID, "playerUUID");
        Objects.requireNonNull(block, "block");
        Protection protection = api.findProtection(block);
        UUID owner = protection != null ? protection.getOwner() : null;
        return playerUUID.equals(owner);
    }

    /**
     * Returns all blocks protected by the specified player within the given bounding box and world.
     *
     * @param playerUUID  the owner's UUID (not null)
     * @param boundingBox the search area (not null)
     * @param world       the world (not null)
     * @return list of protected blocks (never null)
     */
    @Override
    public @NotNull List<Block> getProtectedBlocks(@NotNull UUID playerUUID, @NotNull BoundingBox boundingBox, @NotNull World world) {
        Objects.requireNonNull(playerUUID, "playerUUID");
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(world, "world");

        Collection<Protection> protections = api.findProtections(world, boundingBox);
        if (protections == null || protections.isEmpty()) {
            return List.of();
        }

        List<Block> protectedBlocks = new ArrayList<>();
        for (Protection protection : protections) {
            if (protection instanceof BlockProtection blockProtection && playerUUID.equals(protection.getOwner())) {
                int x = blockProtection.getX();
                int y = blockProtection.getY();
                int z = blockProtection.getZ();

                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;

                Chunk chunk = world.getChunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (!chunk.isLoaded()) chunk.load();

                Block b = chunk.getBlock(x & 15, y, z & 15);
                protectedBlocks.add(b);
            }
        }

        return protectedBlocks;
    }

    /**
     * Returns all protected blocks within the given bounding box and world, regardless of owner.
     *
     * @param boundingBox the search area (not null)
     * @param world       the world (not null)
     * @return list of protected blocks (never null)
     */
    @Override
    public @NotNull List<Block> getProtectedBlocksIn(@NotNull BoundingBox boundingBox, @NotNull World world) {
        Objects.requireNonNull(boundingBox, "boundingBox");
        Objects.requireNonNull(world, "world");

        Collection<Protection> protections = api.findProtections(world, boundingBox);
        if (protections == null || protections.isEmpty()) {
            return List.of();
        }

        List<Block> protectedBlocks = new ArrayList<>();
        for (Protection protection : protections) {
            if (protection instanceof BlockProtection bp) {
                int x = bp.getX();
                int y = bp.getY();
                int z = bp.getZ();

                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;

                Chunk chunk = world.getChunkAt(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                if (!chunk.isLoaded()) chunk.load();

                Block b = chunk.getBlock(x & 15, y, z & 15);
                protectedBlocks.add(b);
            }
        }

        return protectedBlocks;
    }

    /**
     * Removes a protection for the given block using BoltAPI.
     *
     * @param block the block whose protection should be removed (not null)
     */
    @Override
    public void removeProtection(@NotNull Block block) {
        Objects.requireNonNull(block, "block");
        Protection protection = api.findProtection(block);
        if (protection != null) {
            api.removeProtection(protection);
        }
    }

    /**
     * Creates a protection for the given block owned by the specified player.
     * <p>
     * If the block is part of a double chest, this method will automatically
     * apply the same protection to the adjacent half of the chest.
     *
     * @param block     the block to protect (not null)
     * @param ownerUuid the UUID of the protection owner (not null)
     */
    @Override
    public void createProtection(@NotNull Block block, @NotNull UUID ownerUuid) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(ownerUuid, "ownerUuid");

        // Create protection for the current block
        BlockProtection protection = api.createProtection(block, ownerUuid, "private");
        api.saveProtection(protection);

        // If the block is part of a double chest, also protect the other half
        if (block.getState() instanceof Chest chest) {
            org.bukkit.block.data.type.Chest chestData = (org.bukkit.block.data.type.Chest) chest.getBlockData();
            if (chestData.getType() != Type.SINGLE) {
                BlockFace connectedFace = getConnectedChestFace(chestData);
                Block otherHalf = block.getRelative(connectedFace);

                // Only protect the other half if it isn't already protected
                if (api.findProtection(otherHalf) == null) {
                    BlockProtection secondProtection = api.createProtection(otherHalf, ownerUuid, "private");
                    api.saveProtection(secondProtection);
                }
            }
        }
    }

    /**
     * Determines the facing direction of the other half of a double chest.
     *
     * @param chestData the chest block data (not null)
     * @return the relative face pointing toward the other half of the chest
     */
    private @NotNull BlockFace getConnectedChestFace(@NotNull org.bukkit.block.data.type.Chest chestData) {
        BlockFace facing = chestData.getFacing();
        return switch (chestData.getType()) {
            case LEFT -> switch (facing) {
                case NORTH -> BlockFace.EAST;
                case SOUTH -> BlockFace.WEST;
                case WEST -> BlockFace.NORTH;
                case EAST -> BlockFace.SOUTH;
                default -> BlockFace.SELF;
            };
            case RIGHT -> switch (facing) {
                case NORTH -> BlockFace.WEST;
                case SOUTH -> BlockFace.EAST;
                case WEST -> BlockFace.SOUTH;
                case EAST -> BlockFace.NORTH;
                default -> BlockFace.SELF;
            };
            default -> BlockFace.SELF;
        };
    }
}
