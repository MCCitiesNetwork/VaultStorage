package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.popcraft.bolt.BoltAPI;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.*;

/**
 * Service implementation that integrates with Bolt to query and manage block protections.
 * This implementation delegates double chest handling to higher-level services.
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
     *
     * @param block     the block to protect (not null)
     * @param ownerUuid the UUID of the protection owner (not null)
     */
    @Override
    public void createProtection(@NotNull Block block, @NotNull UUID ownerUuid) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        BlockProtection protection = api.createProtection(block, ownerUuid, "private");
        api.saveProtection(protection);
    }
}
