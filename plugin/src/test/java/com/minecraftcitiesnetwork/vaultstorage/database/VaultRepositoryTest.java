package com.minecraftcitiesnetwork.vaultstorage.database;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VaultRepositoryTest extends DatabaseTestBase {

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER_WORLD = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID OWNER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID OTHER_OWNER = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
    private static final int MAX_SLOTS = 27;

    private static VaultRepository repository;

    @BeforeAll
    static void migrateOnce() throws SQLException {
        dropEverything();
        migrate();
        repository = new VaultRepository(new VaultDatabase(dataSource()));
    }

    @BeforeEach
    void emptyTheTables() throws SQLException {
        runStatements("DELETE FROM VaultItem", "DELETE FROM Vault", "DELETE FROM World");
    }

    private static NewVault vaultFor(UUID owner, UUID world, int x) {
        return new NewVault(world, world.equals(WORLD) ? "city" : "wilds", x, 64, -20, owner, "CHEST",
                "minecraft:chest[facing=north]");
    }

    private static VaultItem item(int slot) {
        return new VaultItem(slot, 1, new byte[] {(byte) slot, 2, 3});
    }

    private static StoredVault found(UUID vault) {
        return Assertions.assertInstanceOf(FindResult.Found.class, repository.find(vault)).vault();
    }

    @Test
    void aCreatedVaultIsFoundWithItsOwnerAndItems() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of(item(0), item(5)));
        StoredVault stored = found(vault);
        Assertions.assertEquals(OWNER, stored.vault().ownerUuid());
        Assertions.assertEquals(WORLD, stored.vault().worldUuid());
        Assertions.assertEquals(10, stored.vault().x());
        Assertions.assertEquals("CHEST", stored.vault().material());
        Assertions.assertEquals(List.of(0, 5), stored.items().stream().map(VaultItemEntity::slot).toList());
        Assertions.assertArrayEquals(new byte[] {5, 2, 3}, stored.items().get(1).item());
    }

    @Test
    void anUnknownVaultIsNotFound() {
        Assertions.assertInstanceOf(FindResult.NoSuchVault.class, repository.find(UUID.randomUUID()));
    }

    @Test
    void aFailedItemInsertLeavesNoVaultBehind() throws SQLException {
        // Two items in one slot violate the primary key, after the vault row was written.
        Assertions.assertThrows(RuntimeException.class,
                () -> repository.create(vaultFor(OWNER, WORLD, 10), List.of(item(3), item(3))));
        Assertions.assertEquals(0, queryLong("SELECT COUNT(*) FROM Vault"));
        Assertions.assertEquals(0, queryLong("SELECT COUNT(*) FROM VaultItem"));
    }

    @Test
    void aHundredKilobyteItemRoundTrips() {
        byte[] large = new byte[100_000];
        Arrays.fill(large, (byte) 9);
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of(new VaultItem(0, 1, large)));
        Assertions.assertArrayEquals(large, found(vault).items().getFirst().item());
    }

    @Test
    void aVaultCanBeCreatedInAWorldSeenForTheFirstTimeOrRenamed() throws SQLException {
        repository.create(vaultFor(OWNER, WORLD, 10), List.of(item(0)));
        // The same world under a new name, and a second world reusing the first one's old name.
        repository.create(new NewVault(WORLD, "city-renamed", 11, 64, -20, OWNER, "CHEST", null), List.of());
        repository.create(new NewVault(OTHER_WORLD, "city", 12, 64, -20, OWNER, "CHEST", null), List.of());
        Assertions.assertEquals(2, queryLong("SELECT COUNT(*) FROM World"));
        Assertions.assertEquals(3, queryLong("SELECT COUNT(*) FROM Vault"));
    }

    @Test
    void aPageIsStableFilteredAndCounted() {
        List<UUID> mine = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            mine.add(repository.create(vaultFor(OWNER, WORLD, x), x == 0 ? List.of(item(0), item(1)) : List.of()));
        }
        repository.create(vaultFor(OWNER, OTHER_WORLD, 100), List.of());
        repository.create(vaultFor(OTHER_OWNER, WORLD, 200), List.of());

        VaultPage everything = repository.page(new VaultFilter(null, null), 0, 50);
        Assertions.assertEquals(7, everything.total());
        Assertions.assertEquals(7, everything.vaults().size());

        VaultPage first = repository.page(new VaultFilter(OWNER, WORLD), 0, 2);
        VaultPage second = repository.page(new VaultFilter(OWNER, WORLD), 2, 2);
        VaultPage third = repository.page(new VaultFilter(OWNER, WORLD), 4, 2);
        Assertions.assertEquals(5, first.total());
        List<UUID> paged = new ArrayList<>();
        for (VaultPage page : List.of(first, second, third)) {
            page.vaults().forEach(summary -> paged.add(summary.uuid()));
        }
        Assertions.assertEquals(5, paged.size());
        Assertions.assertEquals(new HashSet<>(mine), new HashSet<>(paged));
        // Asking again returns the same order.
        Assertions.assertEquals(first.vaults(), repository.page(new VaultFilter(OWNER, WORLD), 0, 2).vaults());

        VaultSummary withItems = everything.vaults().stream()
                .filter(summary -> summary.uuid().equals(mine.getFirst())).findFirst().orElseThrow();
        Assertions.assertEquals(2, withItems.itemCount());
        Assertions.assertEquals(OWNER, withItems.ownerUuid());

        Assertions.assertEquals(6, repository.count(new VaultFilter(OWNER, null)));
        Assertions.assertEquals(6, repository.count(new VaultFilter(null, WORLD)));
    }

    @Test
    void aVaultIsClaimedByOneActorAtATime() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of(item(0)));
        ClaimResult.Claimed claimed = Assertions.assertInstanceOf(ClaimResult.Claimed.class, repository.claim(vault));
        Assertions.assertEquals(1, claimed.vault().items().size());
        Assertions.assertInstanceOf(ClaimResult.AlreadyClaimed.class, repository.claim(vault));
        repository.release(claimed.claim());
        Assertions.assertInstanceOf(ClaimResult.Claimed.class, repository.claim(vault));
        Assertions.assertInstanceOf(ClaimResult.NoSuchVault.class, repository.claim(UUID.randomUUID()));
    }

    @Test
    void onlyTheHolderOfAClaimCanReleaseIt() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of());
        ClaimResult.Claimed claimed = Assertions.assertInstanceOf(ClaimResult.Claimed.class, repository.claim(vault));

        repository.release(new Claim(vault, UUID.randomUUID()));
        Assertions.assertInstanceOf(ClaimResult.AlreadyClaimed.class, repository.claim(vault));

        repository.release(claimed.claim());
        Assertions.assertInstanceOf(ClaimResult.Claimed.class, repository.claim(vault));
    }

    @Test
    void aClaimFromBeforeARestartDoesNotReleaseTheNextHolders() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of());
        ClaimResult.Claimed stale = Assertions.assertInstanceOf(ClaimResult.Claimed.class, repository.claim(vault));
        repository.releaseAllClaims();
        Assertions.assertInstanceOf(ClaimResult.Claimed.class, repository.claim(vault));

        repository.release(stale.claim());

        Assertions.assertInstanceOf(ClaimResult.AlreadyClaimed.class, repository.claim(vault));
    }

    @Test
    void everyClaimIsReleasedAtOnce() {
        UUID first = repository.create(vaultFor(OWNER, WORLD, 10), List.of());
        UUID second = repository.create(vaultFor(OWNER, WORLD, 11), List.of());
        repository.create(vaultFor(OWNER, WORLD, 12), List.of());
        repository.claim(first);
        repository.claim(second);
        Assertions.assertEquals(Set.of(first, second), new HashSet<>(repository.releaseAllClaims()));
        Assertions.assertInstanceOf(ClaimResult.Claimed.class, repository.claim(first));
    }

    @Test
    void appendingUsesTheOldestVaultWithRoom() {
        List<VaultItem> full = new ArrayList<>();
        for (int slot = 0; slot < MAX_SLOTS; slot++) {
            full.add(item(slot));
        }
        repository.create(vaultFor(OWNER, WORLD, 1), full);
        UUID withRoom = repository.create(vaultFor(OWNER, WORLD, 2), List.of(item(0), item(1)));

        AppendResult appended = repository.appendItems(OWNER, List.of(item(0), item(1)), MAX_SLOTS,
                vaultFor(OWNER, WORLD, 3));
        Assertions.assertEquals(withRoom, Assertions.assertInstanceOf(AppendResult.Appended.class, appended).vault());
        Assertions.assertEquals(List.of(0, 1, 2, 3), found(withRoom).items().stream().map(VaultItemEntity::slot).toList());
    }

    @Test
    void appendingCreatesAVaultWhenNoneHasRoom() {
        AppendResult created = repository.appendItems(OWNER, List.of(item(0)), MAX_SLOTS, vaultFor(OWNER, WORLD, 3));
        UUID vault = Assertions.assertInstanceOf(AppendResult.Created.class, created).vault();
        Assertions.assertEquals(1, found(vault).items().size());
        Assertions.assertEquals(3, found(vault).vault().x());
    }

    @Test
    void vaultsThatHoldNothingAreCounted() {
        repository.create(vaultFor(OWNER, WORLD, 1), List.of(item(0)));
        Assertions.assertEquals(0, repository.countWithoutItems());

        repository.create(vaultFor(OWNER, WORLD, 2), List.of());
        repository.create(vaultFor(OTHER_OWNER, WORLD, 3), List.of());
        Assertions.assertEquals(2, repository.countWithoutItems());
    }

    @Test
    void undoingAnAppendTakesBackOnlyWhatItAdded() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 2), List.of(item(0), item(1)));
        AppendResult appended = repository.appendItems(OWNER, List.of(item(0), item(1)), MAX_SLOTS,
                vaultFor(OWNER, WORLD, 3));

        repository.undoAppend(appended);

        Assertions.assertEquals(List.of(0, 1), found(vault).items().stream().map(VaultItemEntity::slot).toList());
    }

    @Test
    void undoingAnAppendThatCreatedAVaultRemovesTheVault() throws SQLException {
        AppendResult created = repository.appendItems(OWNER, List.of(item(0)), MAX_SLOTS, vaultFor(OWNER, WORLD, 3));

        repository.undoAppend(created);

        Assertions.assertInstanceOf(FindResult.NoSuchVault.class, repository.find(created.vault()));
        Assertions.assertEquals(0, queryLong("SELECT COUNT(*) FROM VaultItem"));
    }

    @Test
    void undoingAnAppendLeavesWhatWasAppendedAfterIt() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 2), List.of(item(0)));
        AppendResult first = repository.appendItems(OWNER, List.of(item(0)), MAX_SLOTS, vaultFor(OWNER, WORLD, 3));
        repository.appendItems(OWNER, List.of(item(0)), MAX_SLOTS, vaultFor(OWNER, WORLD, 3));

        repository.undoAppend(first);

        Assertions.assertEquals(List.of(0, 2), found(vault).items().stream().map(VaultItemEntity::slot).toList());
    }

    @Test
    void concurrentAppendsForOneOwnerNeverShareASlot() throws SQLException {
        repository.create(vaultFor(OWNER, WORLD, 1), List.of());
        List<CompletableFuture<AppendResult>> appends = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            appends.add(CompletableFuture.supplyAsync(() ->
                    repository.appendItems(OWNER, List.of(item(0), item(1)), MAX_SLOTS, vaultFor(OWNER, WORLD, 9))));
        }
        appends.forEach(CompletableFuture::join);
        // The primary key would have rejected a shared slot; all 24 items must be there.
        Assertions.assertEquals(24, queryLong("SELECT COUNT(*) FROM VaultItem"));
        Assertions.assertEquals(1, queryLong("SELECT COUNT(*) FROM Vault"));
    }

    @Test
    void replacingItemsSwapsTheWholeContents() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of(item(0), item(1), item(2)));
        Claim claim = claimOf(vault);
        Assertions.assertInstanceOf(ReplaceResult.Replaced.class, repository.replaceItems(claim, List.of(item(7))));
        Assertions.assertEquals(List.of(7), found(vault).items().stream().map(VaultItemEntity::slot).toList());
        Assertions.assertInstanceOf(ReplaceResult.Replaced.class, repository.replaceItems(claim, List.of()));
        Assertions.assertTrue(found(vault).items().isEmpty());
    }

    @Test
    void changingTheOwnerReportsThePreviousOne() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of());
        OwnerResult.Changed changed = Assertions.assertInstanceOf(OwnerResult.Changed.class,
                repository.setOwner(vault, OTHER_OWNER));
        Assertions.assertEquals(OWNER, changed.previous());
        Assertions.assertEquals(OTHER_OWNER, found(vault).vault().ownerUuid());
    }

    @Test
    void deletingRemovesTheVaultAndItsItems() throws SQLException {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of(item(0), item(1)));
        Assertions.assertInstanceOf(DeleteResult.Deleted.class, repository.delete(vault));
        Assertions.assertEquals(0, queryLong("SELECT COUNT(*) FROM VaultItem"));
        Assertions.assertInstanceOf(DeleteResult.NoSuchVault.class, repository.delete(vault));
    }

    @Test
    void writesToAnUnknownVaultSayItIsNotThere() {
        UUID unknown = UUID.randomUUID();
        Assertions.assertInstanceOf(OwnerResult.NoSuchVault.class, repository.setOwner(unknown, OWNER));
        Claim nothing = new Claim(unknown, UUID.randomUUID());
        Assertions.assertInstanceOf(ReplaceResult.NoSuchVault.class, repository.replaceItems(nothing, List.of(item(0))));
        Assertions.assertInstanceOf(DeleteResult.NoSuchVault.class, repository.delete(nothing));
    }

    @Test
    void onlyTheHolderOfAClaimCanSaveToItsVaultOrDeleteIt() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of(item(0)));
        Claim held = claimOf(vault);
        Claim stranger = new Claim(vault, UUID.randomUUID());

        Assertions.assertInstanceOf(ReplaceResult.NotHeld.class, repository.replaceItems(stranger, List.of(item(5))));
        Assertions.assertInstanceOf(DeleteResult.NotHeld.class, repository.delete(stranger));
        Assertions.assertEquals(List.of(0), found(vault).items().stream().map(VaultItemEntity::slot).toList());

        Assertions.assertInstanceOf(DeleteResult.Deleted.class, repository.delete(held));
    }

    @Test
    void anEditWhoseClaimWasLostAtARestartCannotSaveOverTheNextEditors() {
        UUID vault = repository.create(vaultFor(OWNER, WORLD, 10), List.of(item(0)));
        Claim stale = claimOf(vault);
        repository.releaseAllClaims();
        Claim next = claimOf(vault);

        Assertions.assertInstanceOf(ReplaceResult.NotHeld.class, repository.replaceItems(stale, List.of()));
        Assertions.assertInstanceOf(ReplaceResult.Replaced.class, repository.replaceItems(next, List.of(item(3))));
        Assertions.assertEquals(List.of(3), found(vault).items().stream().map(VaultItemEntity::slot).toList());
    }

    private static Claim claimOf(UUID vault) {
        return Assertions.assertInstanceOf(ClaimResult.Claimed.class, repository.claim(vault)).claim();
    }
}
