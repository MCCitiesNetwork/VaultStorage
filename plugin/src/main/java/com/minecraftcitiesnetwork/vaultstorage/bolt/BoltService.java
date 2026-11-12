package com.minecraftcitiesnetwork.vaultstorage.bolt;

import com.minecraftcitiesnetwork.vaultstorage.Service;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface BoltService extends Service {

    @NotNull UUID getOwner(@NotNull Block block);
    @NotNull UUID getOwner(@NotNull Entity entity);

    boolean isOwner(@NotNull UUID playerUUID, @NotNull Block block);
    boolean isOwner(@NotNull UUID playerUUID, @NotNull Entity entity);



    /**
     * Removes the Bolt protection for the given block if one exists.
     * Implementations should be safe to call on the main thread.
     * @param block target block
     */
    void removeProtection(@NotNull Block block);




    @NotNull Map<EntityProtection, Entity> getProtectedEntities(@NotNull World world, @NotNull BoundingBox boundingBox);

    /**
     * Creates a Bolt protection for the given block owned by the specified player.
     * Implementations should be safe to call on the main thread.
     * @param block target block
     * @param ownerUuid owner of the protection
     */
    void createProtection(@NotNull Block block, @NotNull UUID ownerUuid);

    void removeProtection(@NotNull Protection protection);

    @NotNull Collection<Protection> getProtections(@NotNull BoundingBox boundingBox, @NotNull World world);

}
