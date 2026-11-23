package com.minecraftcitiesnetwork.vaultstorage.database;

import com.minecraftcitiesnetwork.vaultstorage.placement.PlacementStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Every way the plugin reads or writes vaults. One method is one unit of work: it opens one
 * session, and anything with more than one statement is one transaction.
 *
 * <p>Every method blocks on the database and must be called off the main thread. Outcomes a
 * caller must handle are result types; only a broken database is an exception.
 */
public final class VaultRepository implements VaultStore, PlacementStore {

    private final @NotNull VaultDatabase database;
    private final @NotNull Clock clock;

    public VaultRepository(@NotNull VaultDatabase database) {
        this(database, Clock.systemUTC());
    }

    public VaultRepository(@NotNull VaultDatabase database, @NotNull Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    /** The column holds milliseconds, so anything finer would not read back equal. */
    private @NotNull Instant now() {
        return this.clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static @NotNull List<VaultItemEntity> rows(@NotNull UUID vault, @NotNull List<VaultItem> items, int firstSlot) {
        List<VaultItemEntity> rows = new ArrayList<>(items.size());
        for (VaultItem item : items) {
            rows.add(new VaultItemEntity(vault, firstSlot + item.slot(), item.amount(), item.item()));
        }
        return rows;
    }

    private @NotNull UUID insertVault(@NotNull SqlSessionWrapper wrapper, @NotNull NewVault vault) {
        UUID uuid = UUID.randomUUID();
        Instant now = now();
        wrapper.worlds().upsert(vault.worldUuid(), vault.worldName());
        wrapper.vaults().insert(new VaultEntity(uuid, vault.ownerUuid(), vault.worldUuid(), vault.x(), vault.y(),
                vault.z(), vault.material(), vault.blockData(), null, now, now));
        return uuid;
    }

    /** Creates a vault and its items together: either all of it is stored or none of it. */
    @Override
    public @NotNull UUID create(@NotNull NewVault vault, @NotNull List<VaultItem> items) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            UUID uuid = insertVault(wrapper, vault);
            if (!items.isEmpty()) {
                wrapper.items().insertAll(rows(uuid, items, 0));
            }
            wrapper.session().commit();
            return uuid;
        }
    }

    public @NotNull FindResult find(@NotNull UUID vault) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            VaultEntity entity = wrapper.vaults().selectByUuid(vault);
            if (entity == null) {
                return new FindResult.NoSuchVault();
            }
            return new FindResult.Found(new StoredVault(entity, wrapper.items().selectByVault(vault)));
        }
    }

    public @NotNull VaultPage page(@NotNull VaultFilter filter, int offset, int limit) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            int total = wrapper.vaults().count(filter.ownerUuid(), filter.worldUuid());
            List<VaultSummary> vaults = wrapper.vaults().selectPage(filter.ownerUuid(), filter.worldUuid(),
                    Math.max(0, offset), Math.max(1, limit));
            return new VaultPage(vaults, total);
        }
    }

    public int count(@NotNull VaultFilter filter) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            return wrapper.vaults().count(filter.ownerUuid(), filter.worldUuid());
        }
    }

    /** Deletes a vault; its items go with it. */
    @Override
    public @NotNull DeleteResult delete(@NotNull UUID vault) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            return wrapper.vaults().delete(vault) > 0 ? new DeleteResult.Deleted() : new DeleteResult.NoSuchVault();
        }
    }

    /** Reads before it writes, because the caller reports who owned the vault before. */
    public @NotNull OwnerResult setOwner(@NotNull UUID vault, @NotNull UUID owner) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            UUID previous = wrapper.vaults().selectOwnerForUpdate(vault);
            if (previous == null) {
                return new OwnerResult.NoSuchVault();
            }
            wrapper.vaults().updateOwner(vault, owner, now());
            wrapper.session().commit();
            return new OwnerResult.Changed(previous);
        }
    }


    /** Swaps a vault's whole contents in one transaction. */
    public @NotNull ReplaceResult replaceItems(@NotNull UUID vault, @NotNull List<VaultItem> items) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            if (wrapper.vaults().touch(vault, now()) == 0) {
                return new ReplaceResult.NoSuchVault();
            }
            wrapper.items().deleteByVault(vault);
            if (!items.isEmpty()) {
                wrapper.items().insertAll(rows(vault, items, 0));
            }
            wrapper.session().commit();
            return new ReplaceResult.Replaced();
        }
    }

    /**
     * Adds items after the last used slot of the owner's oldest vault with room, or creates
     * {@code ifNoneFits} to hold them. An item's slot is its offset: 0 is the first free slot.
     * The chosen vault's rows are locked, so two appends for one owner cannot pick the same slots.
     */
    public @NotNull AppendResult appendItems(@NotNull UUID owner, @NotNull List<VaultItem> items, int maxSlots,
                                             @NotNull NewVault ifNoneFits) {
        int needed = items.stream().mapToInt(VaultItem::slot).max().orElse(-1) + 1;
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            VaultRoom room = wrapper.vaults().selectOldestWithRoom(owner, needed, maxSlots);
            AppendResult result;
            if (room == null) {
                UUID created = insertVault(wrapper, ifNoneFits);
                if (!items.isEmpty()) {
                    wrapper.items().insertAll(rows(created, items, 0));
                }
                result = new AppendResult.Created(created);
            } else {
                if (!items.isEmpty()) {
                    wrapper.items().insertAll(rows(room.uuid(), items, room.highestSlot() + 1));
                }
                wrapper.vaults().touch(room.uuid(), now());
                result = new AppendResult.Appended(room.uuid(), room.highestSlot() + 1, needed);
            }
            wrapper.session().commit();
            return result;
        }
    }

    /** How many vaults hold nothing. */
    public int countWithoutItems() {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            return wrapper.vaults().countWithoutItems();
        }
    }

    /**
     * Takes back an append: the slots it filled, or the vault it created. For a capture that
     * stored something and then found it changed or gone in the world.
     */
    public void undoAppend(@NotNull AppendResult append) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            switch (append) {
                case AppendResult.Appended appended -> wrapper.items().deleteSlots(appended.vault(),
                        appended.firstSlot(), appended.firstSlot() + appended.slots() - 1);
                case AppendResult.Created created -> {
                    wrapper.items().deleteByVault(created.vault());
                    wrapper.vaults().delete(created.vault());
                }
            }
            wrapper.session().commit();
        }
    }

    /**
     * Takes a vault for one actor, to place or edit it. A compare-and-set in the database: of
     * two callers, exactly one gets {@link ClaimResult.Claimed}.
     */
    @Override
    public @NotNull ClaimResult claim(@NotNull UUID vault) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            if (wrapper.vaults().claim(vault) == 0) {
                return wrapper.vaults().selectByUuid(vault) == null
                        ? new ClaimResult.NoSuchVault() : new ClaimResult.AlreadyClaimed();
            }
            VaultEntity entity = wrapper.vaults().selectByUuid(vault);
            if (entity == null) {
                return new ClaimResult.NoSuchVault();
            }
            StoredVault stored = new StoredVault(entity, wrapper.items().selectByVault(vault));
            wrapper.session().commit();
            return new ClaimResult.Claimed(stored);
        }
    }

    /** Gives a claimed vault back. Does nothing if the vault is gone. */
    @Override
    public void release(@NotNull UUID vault) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            wrapper.vaults().release(vault);
        }
    }

    /** Releases every claim, for startup: a claim can only be left behind by a crash. */
    public @NotNull List<UUID> releaseAllClaims() {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            List<UUID> claimed = wrapper.vaults().selectClaimed();
            if (!claimed.isEmpty()) {
                wrapper.vaults().releaseAll();
            }
            wrapper.session().commit();
            return claimed;
        }
    }
}
