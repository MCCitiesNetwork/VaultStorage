package net.democracycraft.vault.internal.database.dao;

import net.democracycraft.vault.api.dao.VaultDAO;
import net.democracycraft.vault.internal.database.DatabaseSchema;
import net.democracycraft.vault.internal.database.entity.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public record VaultDAOImpl(DatabaseSchema schema) implements VaultDAO {

    public VaultDAOImpl(@NotNull DatabaseSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public void createVault(@NotNull VaultEntity vault, @NotNull UUID ownerUuid) {
        Objects.requireNonNull(vault, "vault");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        // Insert vault row first
        schema.vaults().insertOrUpdateSync(vault);
        // Verify persistence
        VaultEntity persisted = schema.vaults().findBy("uuid", vault.uuid);
        if (persisted == null) {
            throw new IllegalStateException("Vault row was not persisted for uuid " + vault.uuid);
        }
        // Insert owner row
        VaultOwnerEntity owner = new VaultOwnerEntity();
        owner.vaultUuid = vault.uuid;
        owner.ownerUuid = ownerUuid;
        schema.vaultOwners().insertOrUpdateSync(owner);
    }

    @Override
    public @Nullable VaultEntity getVault(@NotNull UUID vaultUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        return schema.vaults().findBy("uuid", vaultUuid);
    }

    @Override
    public @Nullable VaultEntity findByLocation(@NotNull UUID worldUuid, int x, int y, int z) {
        Objects.requireNonNull(worldUuid, "worldUuid");
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("worldUuid", worldUuid);
        where.put("x", x);
        where.put("y", y);
        where.put("z", z);
        List<VaultEntity> list = schema.vaults().findAllByMany(where, null);
        return list.isEmpty() ? null : list.getFirst();
    }

    @Override
    public void deleteVault(@NotNull UUID vaultUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        schema.vaults().deleteById(vaultUuid);
    }

    @Override
    public void setOwner(@NotNull UUID vaultUuid, @NotNull UUID ownerUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        VaultOwnerEntity vaultOwnerEntity = new VaultOwnerEntity();
        vaultOwnerEntity.vaultUuid = vaultUuid;
        vaultOwnerEntity.ownerUuid = ownerUuid;
        schema.vaultOwners().insertOrUpdateSync(vaultOwnerEntity);
    }

    @Override
    public @Nullable UUID getOwner(@NotNull UUID vaultUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        VaultOwnerEntity vaultUuid1 = schema.vaultOwners().findBy("vaultUuid", vaultUuid);
        return vaultUuid1 != null ? vaultUuid1.ownerUuid : null;
    }

    @Override
    public @NotNull List<VaultEntity> listByOwner(@NotNull UUID ownerUuid) {
        Objects.requireNonNull(ownerUuid, "ownerUuid");
        List<VaultOwnerEntity> owners = schema.vaultOwners().findAllBy("ownerUuid", ownerUuid, null);
        List<VaultEntity> out = new ArrayList<>(owners.size());
        for (VaultOwnerEntity vaultOwnerEntity : owners) {
            VaultEntity vaultEntity = schema.vaults().findBy("uuid", vaultOwnerEntity.vaultUuid);
            if (vaultEntity != null) out.add(vaultEntity);
        }
        return out;
    }

    @Override
    public @NotNull List<VaultEntity> listInWorld(@NotNull UUID worldUuid) {
        Objects.requireNonNull(worldUuid, "worldUuid");
        return schema.vaults().findAllBy("worldUuid", worldUuid, null);
    }

    @Override
    public void putItem(@NotNull UUID vaultUuid, int slot, int amount, byte[] itemBytes) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("vaultUuid", vaultUuid);
        where.put("slot", slot);
        List<VaultItemEntity> rows = schema.vaultItems().findAllByMany(where, null);
        VaultItemEntity itemEntity;
        if (rows.isEmpty()) {
            itemEntity = new VaultItemEntity();
            itemEntity.uuid = UUID.randomUUID();
            itemEntity.vaultUuid = vaultUuid;
            itemEntity.slot = slot;
        } else {
            itemEntity = rows.getFirst();
        }
        itemEntity.amount = amount;
        itemEntity.item = itemBytes;
        schema.vaultItems().insertOrUpdateSync(itemEntity);
    }

    /**
     * Batch insert/update items for a vault.
     * Strategy: generate synthetic UUIDs; rely on unique (vaultUuid,slot) to trigger ON DUPLICATE KEY UPDATE.
     */
    @Override
    public void putItems(@NotNull UUID vaultUuid, @NotNull List<VaultItemEntity> items) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        Objects.requireNonNull(items, "items");
        if (items.isEmpty()) return;
        // Ensure required fields; assign random UUIDs to leverage ON DUPLICATE for unique slot constraint.
        for (VaultItemEntity row : items) {
            if (row.vaultUuid == null) row.vaultUuid = vaultUuid;
            if (row.uuid == null) row.uuid = UUID.randomUUID();
        }
        schema.vaultItems().insertBatchSync(items);
    }

    @Override
    public void removeItem(@NotNull UUID vaultUuid, int slot) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("vaultUuid", vaultUuid);
        where.put("slot", slot);
        schema.vaultItems().deleteWhereSync(where);
    }

    @Override
    public @NotNull List<VaultItemEntity> listItems(@NotNull UUID vaultUuid) {
        Objects.requireNonNull(vaultUuid, "vaultUuid");
        return schema.vaultItems().findAllBy("vaultUuid", vaultUuid, "slot");
    }
}
