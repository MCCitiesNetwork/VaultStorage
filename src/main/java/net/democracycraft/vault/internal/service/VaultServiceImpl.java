package net.democracycraft.vault.internal.service;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.dao.VaultDAO;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.internal.database.entity.VaultEntity;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.config.MailConfig;

public record VaultServiceImpl(VaultDAO dao) implements VaultService {

    public VaultServiceImpl(@NotNull VaultDAO dao) {
        this.dao = Objects.requireNonNull(dao, "dao");
    }

    @Override
    public @NotNull VaultEntity createVault(@NotNull UUID worldUuid, @NotNull UUID actor, int x, int y, int z, @NotNull UUID ownerUuid,
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

        Essentials essentials = VaultStoragePlugin.getInstance().getEssentials();
        User sender = essentials.getUser(actor);



        User recipient = essentials.getUser(ownerUuid);
        if(sender == null || recipient == null) {
            return vaultEntity;
        }
        String senderName = sender.getName();
        String recipientName = recipient.getName();

        // Load configurable MiniMessage template and apply placeholders
        AutoYML<MailConfig> yml = AutoYML.create(MailConfig.class, "mail", DataFolder.MAIL, MailConfig.HEADER);
        MailConfig cfg = yml.loadOrCreate(MailConfig::new);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%sender%", senderName);
        placeholders.put("%recipient%", recipientName);
        addRegionPlaceHolder(worldUuid, x, y, z, placeholders);

        String message = LegacyComponentSerializer.legacySection()
                .serialize(MiniMessageUtil.parseOrPlain(cfg.vaultCreatedMessage, placeholders));

        VaultStoragePlugin.getInstance().getMailService().sendMail(recipient, sender, message);

        return vaultEntity;
    }

    private void addRegionPlaceHolder(@NotNull UUID worldUuid, int x, int y, int z, Map<String, String> placeholders) {
        World world = Bukkit.getWorld(worldUuid);
        if(world != null) {
            Location location = new Location(world, x, y, z);
            List<VaultRegion> regions = VaultStoragePlugin.getInstance().getWorldGuardService().getRegionsAt(location.getBlock());
            String regionName = "N/A";
            if(!regions.isEmpty()) {
                regionName = regions.getFirst().id();
            }
            placeholders.put("%region%", regionName);
        }
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
