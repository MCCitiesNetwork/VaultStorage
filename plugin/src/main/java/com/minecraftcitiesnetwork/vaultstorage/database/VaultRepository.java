package com.minecraftcitiesnetwork.vaultstorage.database;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every way the plugin reads or writes vaults. One method is one unit of work: it opens one
 * session, and anything with more than one statement is one transaction.
 *
 * <p>Every method blocks on the database and must be called off the main thread. Outcomes a
 * caller must handle are result types; only a broken database is an exception.
 */
public final class VaultRepository {

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

    private @NotNull UUID insertVault(@NotNull SqlSessionWrapper wrapper, @NotNull NewVault vault,
                                      @Nullable UUID token) {
        UUID uuid = UUID.randomUUID();
        Instant now = now();
        wrapper.worlds().upsert(vault.worldUuid(), vault.worldName());
        wrapper.vaults().insert(new VaultEntity(uuid, vault.ownerUuid(), vault.worldUuid(), vault.x(), vault.y(),
                vault.z(), vault.material(), vault.blockData(), now, now), token);
        return uuid;
    }

    /** Creates a vault nobody holds, and its items. For tests in this package: a capture uses {@link #createClaimed}. */
    @NotNull UUID create(@NotNull NewVault vault, @NotNull List<VaultItem> items) {
        return create(vault, items, null);
    }

    /**
     * Creates a vault already held by the caller, and its items: either all of it is stored or
     * none of it. For a capture, which may still have to take the vault back: until it lets go,
     * nobody can open, place or transfer what it stored.
     */
    public @NotNull Claim createClaimed(@NotNull NewVault vault, @NotNull List<VaultItem> items) {
        UUID token = UUID.randomUUID();
        return new Claim(create(vault, items, token), token);
    }

    private @NotNull UUID create(@NotNull NewVault vault, @NotNull List<VaultItem> items, @Nullable UUID token) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            UUID uuid = insertVault(wrapper, vault, token);
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

    /**
     * The page asked for, zero-based, or the last page when the list is shorter than that:
     * a list can shrink between two clicks. One count and one query.
     */
    public @NotNull VaultPage pageAt(@NotNull VaultFilter filter, int requestedPage, int pageSize) {
        int size = Math.max(1, pageSize);
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            int total = wrapper.vaults().count(filter.ownerUuid(), filter.worldUuid());
            int lastPage = Math.max(0, (total - 1) / size);
            int page = Math.min(Math.max(0, requestedPage), lastPage);
            return new VaultPage(wrapper.vaults().selectPage(filter.ownerUuid(), filter.worldUuid(), page * size, size),
                    total);
        }
    }

    public int count(@NotNull VaultFilter filter) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            return wrapper.vaults().count(filter.ownerUuid(), filter.worldUuid());
        }
    }

    /** Deletes the vault this claim holds; its items go with it. Deletes nothing it does not hold. */
    public @NotNull DeleteResult delete(@NotNull Claim claim) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            if (wrapper.vaults().deleteHeld(claim.vault(), claim.token()) > 0) {
                return new DeleteResult.Deleted();
            }
            return exists(wrapper, claim.vault()) ? new DeleteResult.NotHeld() : new DeleteResult.NoSuchVault();
        }
    }

    /** For a write that changed no row: whether that is because someone else holds the vault. */
    private static boolean exists(@NotNull SqlSessionWrapper wrapper, @NotNull UUID vault) {
        return wrapper.vaults().selectByUuid(vault) != null;
    }

    /**
     * Gives the vault to another owner, unless someone holds it. Reads before it writes, because
     * the caller reports who owned the vault before.
     */
    public @NotNull OwnerResult setOwner(@NotNull UUID vault, @NotNull UUID owner) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            UUID previous = wrapper.vaults().selectOwnerForUpdate(vault);
            if (previous == null) {
                return new OwnerResult.NoSuchVault();
            }
            if (wrapper.vaults().updateOwner(vault, owner, now()) == 0) {
                return new OwnerResult.InUse();
            }
            wrapper.session().commit();
            return new OwnerResult.Changed(previous);
        }
    }


    /**
     * Swaps the whole contents of the vault this claim holds, in one transaction. Writes
     * nothing to a vault it does not hold: that one is someone else's by now.
     */
    public @NotNull ReplaceResult replaceItems(@NotNull Claim claim, @NotNull List<VaultItem> items) {
        UUID vault = claim.vault();
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            if (wrapper.vaults().touchHeld(vault, claim.token(), now()) == 0) {
                return exists(wrapper, vault) ? new ReplaceResult.NotHeld() : new ReplaceResult.NoSuchVault();
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
                UUID created = insertVault(wrapper, ifNoneFits, null);
                if (!items.isEmpty()) {
                    wrapper.items().insertAll(rows(created, items, 0));
                }
                result = new AppendResult(created, 0, needed, true);
            } else {
                if (!items.isEmpty()) {
                    wrapper.items().insertAll(rows(room.uuid(), items, room.highestSlot() + 1));
                }
                wrapper.vaults().touch(room.uuid(), now());
                result = new AppendResult(room.uuid(), room.highestSlot() + 1, needed, false);
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
     * Takes back an append: the slots it filled, and the vault it created if that is empty
     * again. For a capture that stored something and then found it changed or gone in the world.
     *
     * @return false when someone holds the vault by now, or it no longer has those slots.
     *         Nothing was taken back: what they see and save includes the appended items.
     */
    public boolean undoAppend(@NotNull AppendResult append) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            int removed = wrapper.items().deleteFreeSlots(append.vault(), append.firstSlot(),
                    append.firstSlot() + append.slots() - 1);
            if (removed == 0 && append.slots() > 0) {
                return false;
            }
            if (append.created()) {
                wrapper.vaults().deleteFreeIfEmpty(append.vault());
            }
            wrapper.session().commit();
            return true;
        }
    }

    /**
     * Takes a vault for one actor, to place, edit or delete it. A compare-and-set in the
     * database: of two callers, exactly one gets {@link ClaimResult.Claimed}, with the claim
     * that releases it.
     */
    public @NotNull ClaimResult claim(@NotNull UUID vault) {
        try (SqlSessionWrapper wrapper = this.database.openSession()) {
            Claim claim = new Claim(vault, UUID.randomUUID());
            if (wrapper.vaults().claim(vault, claim.token()) == 0) {
                return exists(wrapper, vault) ? new ClaimResult.AlreadyClaimed() : new ClaimResult.NoSuchVault();
            }
            VaultEntity entity = wrapper.vaults().selectByUuid(vault);
            if (entity == null) {
                return new ClaimResult.NoSuchVault();
            }
            StoredVault stored = new StoredVault(entity, wrapper.items().selectByVault(vault));
            wrapper.session().commit();
            return new ClaimResult.Claimed(stored, claim);
        }
    }

    /**
     * Gives a claimed vault back. Does nothing if the vault is gone or is no longer held with
     * this claim: it was released at a restart and may be someone else's by now.
     */
    public void release(@NotNull Claim claim) {
        try (SqlSessionWrapper wrapper = this.database.openSession(true)) {
            wrapper.vaults().release(claim.vault(), claim.token());
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
