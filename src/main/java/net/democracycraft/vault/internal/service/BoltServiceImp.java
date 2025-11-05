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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Service implementation that integrates with Bolt to query protections.
 */
public class BoltServiceImp implements BoltService {

    private final BoltAPI api;

    private final VaultStoragePlugin plugin;

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
     * Gets the owner of the given block's protection if one exists.
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
     * @return true if the player owns the block's protection; false if not protected or owned by someone else
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
     * Coordinate note: chunk coordinates differ from block coordinates. To map a block at (x, z) to its chunk,
     * use floor division by 16 (i.e., chunkX = floorDiv(x, 16), chunkZ = floorDiv(z, 16)). This is equivalent to
     * an arithmetic right shift by 4 (x >> 4), but Math.floorDiv is clearer and correct for negatives.
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

        List<BlockProtection> ownerBlockProtections = new ArrayList<>();
        protections.stream()
                .filter(p -> p != null && playerUUID.equals(p.getOwner()))
                .forEach(p -> {
                    if (p instanceof BlockProtection blockProtection) {
                        ownerBlockProtections.add(blockProtection);
                    }
                });

        if (ownerBlockProtections.isEmpty()) {
            return List.of();
        }

        List<Block> protectedBlocks = new ArrayList<>(ownerBlockProtections.size());
        for (BlockProtection blockProtection : ownerBlockProtections) {
            int x = blockProtection.getX();
            int y = blockProtection.getY();
            int z = blockProtection.getZ();

            // Guard against out-of-bounds Y just in case.
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }

            int chunkX = Math.floorDiv(x, 16);
            int chunkZ = Math.floorDiv(z, 16);
            Chunk containerChunk = world.getChunkAt(chunkX, chunkZ);

            Block b = containerChunk.getBlock(x, y, z);
            protectedBlocks.add(b);
        }

        return protectedBlocks;
    }
}
