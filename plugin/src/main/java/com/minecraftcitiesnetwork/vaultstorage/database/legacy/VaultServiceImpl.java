package com.minecraftcitiesnetwork.vaultstorage.database.legacy;

import com.minecraftcitiesnetwork.vaultstorage.database.FindResult;
import com.minecraftcitiesnetwork.vaultstorage.database.NewVault;
import com.minecraftcitiesnetwork.vaultstorage.database.StoredVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultFilter;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultRepository;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultSummary;
import com.minecraftcitiesnetwork.vaultstorage.database.legacy.VaultEntity;
import com.minecraftcitiesnetwork.vaultstorage.database.legacy.VaultItemEntity;
import com.minecraftcitiesnetwork.vaultstorage.database.legacy.VaultService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** {@link VaultService} over the repository. Lists are unpaged, as their callers still expect. */
public final class VaultServiceImpl implements VaultService {

    /** More than any list can hold; the callers that page are written in a later step. */
    private static final int UNPAGED = 1_000_000;
    private static final String DEFAULT_MATERIAL = "CHEST";

    private final @NotNull VaultRepository repository;

    public VaultServiceImpl(@NotNull VaultRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    private static @NotNull VaultEntity legacy(@NotNull com.minecraftcitiesnetwork.vaultstorage.database.VaultEntity vault) {
        VaultEntity entity = new VaultEntity();
        entity.uuid = vault.uuid();
        entity.worldUuid = vault.worldUuid();
        entity.x = vault.x();
        entity.y = vault.y();
        entity.z = vault.z();
        entity.material = vault.material();
        entity.blockData = vault.blockData();
        entity.status = vault.status();
        entity.createdAtEpochMillis = vault.createdTime().toEpochMilli();
        entity.updatedAtEpochMillis = vault.updatedTime().toEpochMilli();
        return entity;
    }

    private static @NotNull VaultEntity legacy(@NotNull VaultSummary summary) {
        VaultEntity entity = new VaultEntity();
        entity.uuid = summary.uuid();
        entity.worldUuid = summary.worldUuid();
        entity.x = summary.x();
        entity.y = summary.y();
        entity.z = summary.z();
        entity.material = summary.material();
        entity.createdAtEpochMillis = summary.createdTime().toEpochMilli();
        entity.updatedAtEpochMillis = entity.createdAtEpochMillis;
        return entity;
    }

    private static @NotNull List<VaultItem> items(@NotNull List<VaultItemEntity> rows) {
        List<VaultItem> items = new ArrayList<>(rows.size());
        for (VaultItemEntity row : rows) {
            if (row.item != null) {
                items.add(new VaultItem(row.slot, row.amount, row.item));
            }
        }
        return items;
    }

    private @NotNull Optional<StoredVault> stored(@NotNull UUID vaultUuid) {
        return this.repository.find(vaultUuid) instanceof FindResult.Found found
                ? Optional.of(found.vault()) : Optional.empty();
    }

    private @NotNull List<VaultEntity> list(@NotNull VaultFilter filter) {
        List<VaultEntity> out = new ArrayList<>();
        for (VaultSummary summary : this.repository.page(filter, 0, UNPAGED).vaults()) {
            out.add(legacy(summary));
        }
        return out;
    }

    @Override
    public @NotNull VaultEntity createVault(@NotNull UUID worldUuid, @NotNull UUID actor, int x, int y, int z,
                                            @NotNull UUID ownerUuid, @Nullable String material,
                                            @Nullable String blockData) {
        World bukkitWorld = Bukkit.getWorld(worldUuid);
        String worldName = bukkitWorld != null ? bukkitWorld.getName() : worldUuid.toString();
        String storedMaterial = material != null && !material.isBlank() ? material : DEFAULT_MATERIAL;
        UUID created = this.repository.create(
                new NewVault(worldUuid, worldName, x, y, z, ownerUuid, storedMaterial, blockData), List.of());
        VaultEntity vaultEntity = new VaultEntity();
        vaultEntity.uuid = created;
        vaultEntity.worldUuid = worldUuid;
        vaultEntity.x = x;
        vaultEntity.y = y;
        vaultEntity.z = z;
        vaultEntity.material = storedMaterial;
        vaultEntity.blockData = blockData;

        return vaultEntity;
    }

    @Override
    public @NotNull Optional<VaultEntity> get(@NotNull UUID vaultUuid) {
        return stored(vaultUuid).map(vault -> legacy(vault.vault()));
    }

    @Override
    public void delete(@NotNull UUID vaultUuid) {
        this.repository.delete(vaultUuid);
    }

    @Override
    public void setOwner(@NotNull UUID vaultUuid, @NotNull UUID ownerUuid) {
        this.repository.setOwner(vaultUuid, ownerUuid);
    }

    @Override
    public @Nullable UUID getOwner(@NotNull UUID vaultUuid) {
        return stored(vaultUuid).map(vault -> vault.vault().ownerUuid()).orElse(null);
    }

    @Override
    public @NotNull List<VaultEntity> listByOwner(@NotNull UUID ownerUuid) {
        return list(new VaultFilter(ownerUuid, null));
    }

    @Override
    public @NotNull List<VaultEntity> listInWorld(@NotNull UUID worldUuid) {
        return list(new VaultFilter(null, worldUuid));
    }

    @Override
    public @NotNull List<VaultEntity> listByOwnerUuidString(@NotNull String ownerUuidString) {
        try {
            return listByOwner(UUID.fromString(ownerUuidString));
        } catch (IllegalArgumentException notAUuid) {
            return List.of();
        }
    }

    @Override
    public @NotNull List<VaultEntity> listByVaultUuidString(@NotNull String vaultUuidString) {
        try {
            return get(UUID.fromString(vaultUuidString)).map(List::of).orElse(List.of());
        } catch (IllegalArgumentException notAUuid) {
            return List.of();
        }
    }

    @Override
    public void putItems(@NotNull UUID vaultUuid, @NotNull List<VaultItemEntity> items) {
        if (!items.isEmpty()) {
            this.repository.addItems(vaultUuid, items(items));
        }
    }

    @Override
    public void replaceItems(@NotNull UUID vaultUuid, @NotNull List<VaultItemEntity> items) {
        this.repository.replaceItems(vaultUuid, items(items));
    }

    @Override
    public @NotNull List<VaultItemEntity> listItems(@NotNull UUID vaultUuid) {
        List<VaultItemEntity> out = new ArrayList<>();
        for (var row : stored(vaultUuid).map(StoredVault::items).orElse(List.of())) {
            VaultItemEntity entity = new VaultItemEntity();
            entity.vaultUuid = row.vaultUuid();
            entity.slot = row.slot();
            entity.amount = row.amount();
            entity.item = row.item();
            out.add(entity);
        }
        return out;
    }
}
