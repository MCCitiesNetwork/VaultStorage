package com.minecraftcitiesnetwork.vaultstorage.bolt;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.popcraft.bolt.BoltAPI;
import org.popcraft.bolt.protection.BlockProtection;
import org.popcraft.bolt.protection.EntityProtection;
import org.popcraft.bolt.protection.Protection;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service implementation that integrates with Bolt to query and manage block protections.
 * This implementation delegates double chest handling to higher-level services.
 */
public final class BoltServiceImp implements BoltService {

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
     * Gets the owner of the given entity's protection, if one exists.
     *
     * @param entity the entity to check (not null)
     * @return the owner's UUID, or null if the block is not protected
     */
    @Override
    public @Nullable UUID getOwner(@NotNull Entity entity) {
        Objects.requireNonNull(entity, "entity");
        Protection protection = api.findProtection(entity);
        return protection != null ? protection.getOwner() : null;
    }






    @Override
    public @NotNull Map<EntityProtection, Entity> getProtectedEntities(@NotNull World world, @NotNull BoundingBox boundingBox) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(boundingBox, "boundingBox");

        Collection<Protection> protections = api.findProtections(world, boundingBox);
        Map<EntityProtection, Entity> protectedEntities = new HashMap<>();

        for (Protection protection : protections) {
            if (protection instanceof EntityProtection entityProtection) {


                UUID entityUUID = entityProtection.getId();

                Entity entity = world.getEntity(entityUUID);
                if (entity != null) {
                    protectedEntities.put(entityProtection, entity);
                }
            }
        }

        return protectedEntities;
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

    @Override
    public void removeProtection(@NotNull Protection protection) {
        api.removeProtection(protection);
    }

    @Override
    public @NotNull Collection<Protection> getProtections(@NotNull BoundingBox boundingBox, @NotNull World world) {
        return api.findProtections(world, boundingBox);
    }
}
