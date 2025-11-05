package net.democracycraft.vault.api.service;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

import java.util.List;
import java.util.UUID;

public interface BoltService {

    UUID getOwner(Block block);

    boolean isOwner(UUID playerUUID, Block block);

    List<Block> getProtectedBlocks(UUID playerUUID, BoundingBox boundingBox, World world);

    /**
     * Returns all protected blocks within the given bounding box in a world, regardless of owner.
     *
     */
    List<Block> getProtectedBlocksIn(BoundingBox boundingBox, World world);

}
