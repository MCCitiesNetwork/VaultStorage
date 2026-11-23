package com.minecraftcitiesnetwork.vaultstorage.placement;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.database.Claim;
import com.minecraftcitiesnetwork.vaultstorage.database.ClaimResult;
import com.minecraftcitiesnetwork.vaultstorage.database.DeleteResult;
import com.minecraftcitiesnetwork.vaultstorage.database.StoredVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultEntity;
import com.minecraftcitiesnetwork.vaultstorage.placement.VaultPlacementService.Result;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VaultPlacementServiceTest {

    private static final UUID VAULT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Location TARGET = new Location(null, 4, 64, 4);

    /** One vault that can be claimed once, as the database behaves. Records every write in order. */
    private static final class FakeStore implements PlacementStore {
        final List<String> log;
        boolean exists = true;
        boolean claimed;
        boolean deleteFails;

        FakeStore(List<String> log) {
            this.log = log;
        }

        @Override
        public @NotNull ClaimResult claim(@NotNull UUID vault) {
            if (!this.exists) {
                return new ClaimResult.NoSuchVault();
            }
            if (this.claimed) {
                return new ClaimResult.AlreadyClaimed();
            }
            this.claimed = true;
            this.log.add("claim");
            Instant now = Instant.EPOCH;
            return new ClaimResult.Claimed(new StoredVault(new VaultEntity(vault, OWNER, UUID.randomUUID(), 0, 64, 0,
                    "CHEST", null, now, now), List.of()), new Claim(vault, UUID.randomUUID()));
        }

        @Override
        public void release(@NotNull Claim claim) {
            this.claimed = false;
            this.log.add("release");
        }

        @Override
        public @NotNull DeleteResult delete(@NotNull Claim claim) {
            if (this.deleteFails) {
                throw new IllegalStateException("database is down");
            }
            this.exists = false;
            this.log.add("delete");
            return new DeleteResult.Deleted();
        }
    }

    /** A world with one target block that is either air or not. */
    private static final class FakeWorld implements PlacementWorld {
        final List<String> log;
        boolean targetIsAir = true;
        boolean placeThrows;
        boolean protectThrows;

        FakeWorld(List<String> log) {
            this.log = log;
        }

        @Override
        public @NotNull Result place(@NotNull StoredVault vault, @NotNull Location target) {
            if (this.placeThrows) {
                throw new IllegalStateException("chunk unloaded");
            }
            if (!this.targetIsAir) {
                return new Result(false, MessageKeys.PLACEMENT_TARGET_NOT_EMPTY);
            }
            this.targetIsAir = false;
            this.log.add("place");
            return new Result(true, MessageKeys.PLACEMENT_VAULT_PLACED);
        }

        @Override
        public void protect(@NotNull Location target, @NotNull UUID owner) {
            if (this.protectThrows) {
                throw new IllegalStateException("bolt failed");
            }
            this.log.add("protect for " + owner);
        }
    }

    private final List<String> log = new ArrayList<>();
    private final FakeStore store = new FakeStore(this.log);
    private final FakeWorld world = new FakeWorld(this.log);
    private final VaultPlacementService service = new VaultPlacementService(this.store, this.world,
            Runnable::run, Runnable::run, Logger.getAnonymousLogger());

    private Result place() {
        AtomicReference<Result> result = new AtomicReference<>();
        this.service.placeFromDatabaseRelativeAsync(VAULT, TARGET, result::set);
        return result.get();
    }

    @Test
    void aVaultIsClaimedPlacedProtectedAndOnlyThenDeleted() {
        Result result = place();

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(List.of("claim", "place", "protect for " + OWNER, "delete"), this.log);
    }

    @Test
    void aTargetThatIsNoLongerEmptyKeepsTheVaultAndReleasesIt() {
        this.world.targetIsAir = false;

        Result result = place();

        Assertions.assertEquals(new Result(false, MessageKeys.PLACEMENT_TARGET_NOT_EMPTY), result);
        Assertions.assertEquals(List.of("claim", "release"), this.log);
        Assertions.assertTrue(this.store.exists);
        Assertions.assertFalse(this.store.claimed);
    }

    @Test
    void aSecondPlacementOfAVaultBeingPlacedIsRefusedAndTouchesNothing() {
        this.store.claimed = true;

        Result result = place();

        Assertions.assertEquals(new Result(false, MessageKeys.INVENTORY_IN_USE), result);
        Assertions.assertEquals(List.of(), this.log);
    }

    @Test
    void aPlacedVaultCannotBePlacedAgain() {
        place();
        this.log.clear();

        Result second = place();

        Assertions.assertEquals(new Result(false, MessageKeys.INVENTORY_NOT_FOUND), second);
        Assertions.assertEquals(List.of(), this.log);
    }

    @Test
    void aFailureWhilePlacingKeepsTheVaultAndReleasesIt() {
        this.world.placeThrows = true;

        Result result = place();

        Assertions.assertEquals(new Result(false, MessageKeys.PLACEMENT_FAILED), result);
        Assertions.assertEquals(List.of("claim", "release"), this.log);
    }

    @Test
    void aBlockThatCouldNotBeLockedIsStillAPlacedVault() {
        this.world.protectThrows = true;

        Result result = place();

        Assertions.assertTrue(result.success());
        Assertions.assertEquals(List.of("claim", "place", "delete"), this.log);
    }

    @Test
    void aVaultThatCouldNotBeDeletedStaysClaimedSoItIsNotPlacedTwice() {
        this.store.deleteFails = true;

        Result result = place();

        Assertions.assertTrue(result.success());
        Assertions.assertTrue(this.store.claimed);
        Assertions.assertEquals(new Result(false, MessageKeys.INVENTORY_IN_USE), place());
    }
}
