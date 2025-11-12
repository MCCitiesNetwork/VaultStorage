package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.api.convertible.Vault;
import net.democracycraft.vault.api.dao.VaultDAO;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.internal.database.entity.VaultEntity;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;

public record VaultServiceImpl(VaultDAO dao) implements VaultService {

    public VaultServiceImpl(@NotNull VaultDAO dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    public @NotNull VaultEntity createVault(@NotNull UUID worldUuid, int x, int y, int z, @NotNull UUID ownerUuid,
                                     @Nullable String material, @Nullable String blockData) {
        Objects.requireNonNull(worldUuid, "worldUuid");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        VaultEntity vaultEntity = new VaultEntity();
        vaultEntity.uuid = UUID.randomUUID();
        vaultEntity.worldUuid = worldUuid;
        vaultEntity.x = x;
        vaultEntity.y = y;
        vaultEntity.z = z;
        vaultEntity.material = material;
        vaultEntity.blockData = blockData;
        long now = System.currentTimeMillis();
        vaultEntity.createdAtEpochMillis = now;
        vaultEntity.updatedAtEpochMillis = now;
        dao.createVault(vaultEntity, ownerUuid);
        return vaultEntity;
    }

    @Override
    public @NotNull Vault createVault(@NotNull UUID worldUuid, int x, int y, int z, @NotNull UUID ownerUuid,
                                      @Nullable String material, @Nullable String blockData,
                                      @NotNull List<ItemStack> contents) {
        VaultEntity entity = createVault(worldUuid, x, y, z, ownerUuid, material, blockData);
        // Persist items using batch to reduce round-trips.
        if (!contents.isEmpty()) {
            List<VaultItemEntity> batch = new ArrayList<>(contents.size());
            for (int i = 0; i < contents.size(); i++) {
                ItemStack it = contents.get(i);
                if (it == null || it.getAmount() <= 0) continue;
                VaultItemEntity ve = new VaultItemEntity();
                ve.vaultUuid = entity.uuid; // ensured by putItems but set for clarity
                ve.slot = i;
                ve.amount = it.getAmount();
                ve.item = ItemSerialization.toBytes(it);
                batch.add(ve);
            }
            if (!batch.isEmpty()) dao.putItems(entity.uuid, batch);
        }
        World world = Bukkit.getWorld(worldUuid);
        Location loc = world == null ? null : new Location(world, x, y, z);
        Material mat = material == null ? null : safeMaterial(material);
        return new VaultImp(ownerUuid, entity.uuid, contents, mat, loc,
                entity.createdAtEpochMillis == null ? Instant.now() : Instant.ofEpochMilli(entity.createdAtEpochMillis),
                blockData);
    }

    private static Material safeMaterial(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public @NotNull Optional<VaultEntity> get(@NotNull UUID vaultUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        return Optional.ofNullable(dao.getVault(vaultUuid));
    }

    @Override
    public @Nullable VaultEntity findByLocation(@NotNull UUID worldUuid, int x, int y, int z) {
        Objects.requireNonNull(worldUuid, "worldUuid");
        return dao.findByLocation(worldUuid, x, y, z);
    }

    @Override
    public void delete(@NotNull UUID vaultUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        dao.deleteVault(vaultUuid);
    }

    @Override
    public void setOwner(@NotNull UUID vaultUuid, @NotNull UUID ownerUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        dao.setOwner(vaultUuid, ownerUuid);
    }

    @Override
    public @Nullable UUID getOwner(@NotNull UUID vaultUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        return dao.getOwner(vaultUuid);
    }

    @Override
    public @NotNull List<VaultEntity> listByOwner(@NotNull UUID ownerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        return dao.listByOwner(ownerUuid);
    }

    @Override
    public @NotNull List<VaultEntity> listInWorld(@NotNull UUID worldUuid) {
        Objects.requireNonNull(worldUuid, "worldUuid");
        return dao.listInWorld(worldUuid);
    }

    @Override
    public void putItem(@NotNull UUID vaultUuid, int slot, int amount, byte[] itemBytes) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        dao.putItem(vaultUuid, slot, amount, itemBytes);
    }

    /**
     * Batch insert/update implementation delegating to DAO.
     * Assumes all rows belong to the same vaultUuid.
     */
    @Override
    public void putItems(@NotNull UUID vaultUuid, @NotNull List<VaultItemEntity> items) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) return;
        dao.putItems(vaultUuid, items);
    }

    @Override
    public void removeItem(@NotNull UUID vaultUuid, int slot) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        dao.removeItem(vaultUuid, slot);
    }

    @Override
    public @NotNull List<VaultItemEntity> listItems(@NotNull UUID vaultUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        return dao.listItems(vaultUuid);
    }

}
