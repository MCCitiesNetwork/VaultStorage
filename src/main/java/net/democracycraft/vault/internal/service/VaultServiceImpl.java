package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.api.dao.VaultDAO;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.internal.database.entity.VaultEntity;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public record VaultServiceImpl(VaultDAO dao) implements VaultService {

    public VaultServiceImpl(@NotNull VaultDAO dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    public @NotNull UUID createVault(@NotNull UUID worldUuid, int x, int y, int z, @NotNull UUID ownerUuid,
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
        return vaultEntity.uuid;
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
