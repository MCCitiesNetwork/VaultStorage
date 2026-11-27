package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CapturePipelineTest {

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final String CHEST = "chest at 10,64,-20";

    /** Records what happened, in order, so a test can assert the save came before the removal. */
    private final List<String> events = new ArrayList<>();

    private final class FakeStore implements CaptureStore {
        private final Map<UUID, List<VaultItem>> vaults = new HashMap<>();
        private boolean failCreate;
        private boolean failDelete;
        private boolean inUse;

        @Override
        public @NotNull Stored store(@NotNull ContainerSnapshot snapshot, @NotNull UUID owner) {
            if (this.failCreate) {
                throw new IllegalStateException("database down");
            }
            UUID uuid = UUID.randomUUID();
            this.vaults.put(uuid, snapshot.items());
            CapturePipelineTest.this.events.add("created");
            return new Stored(uuid, () -> {
                if (this.failDelete) {
                    throw new IllegalStateException("database down");
                }
                CapturePipelineTest.this.events.add("deleted");
                this.vaults.remove(uuid);
                return !this.inUse;
            }, () -> CapturePipelineTest.this.events.add("settled"));
        }
    }

    private final class FakeAccess implements ContainerAccess<String> {
        private boolean changesAfterSnapshot;
        private final List<String> removed = new ArrayList<>();

        @Override
        public @NotNull ContainerSnapshot snapshot(@NotNull String target) {
            return new ContainerSnapshot(WORLD, "city", 10, 64, -20, "CHEST", "minecraft:chest[facing=north]",
                    List.of(new VaultItem(0, 64, new byte[] {1}), new VaultItem(13, 1, new byte[] {2})));
        }

        @Override
        public boolean stillMatches(@NotNull String target, @NotNull ContainerSnapshot snapshot) {
            return !this.changesAfterSnapshot;
        }

        @Override
        public void remove(@NotNull String target) {
            this.removed.add(target);
            CapturePipelineTest.this.events.add("removed");
        }
    }

    private final FakeStore store = new FakeStore();
    private final FakeAccess access = new FakeAccess();
    private final CapturePipeline<String> pipeline = new CapturePipeline<>(this.store, this.access,
            Runnable::run, Runnable::run, Logger.getLogger("test"));

    @Test
    void theBlockIsRemovedOnlyAfterTheVaultIsStored() {
        CaptureResult result = this.pipeline.capture(CHEST, OWNER).join();
        UUID vault = Assertions.assertInstanceOf(CaptureResult.Vaulted.class, result).vault();
        Assertions.assertEquals(List.of("created", "removed", "settled"), this.events);
        Assertions.assertEquals(List.of(CHEST), this.access.removed);
        // Items keep the slots they had in the container.
        Assertions.assertEquals(List.of(0, 13), this.store.vaults.get(vault).stream().map(VaultItem::slot).toList());
    }

    @Test
    void aContainerThatChangedIsLeftAloneAndItsVaultIsTakenBack() {
        this.access.changesAfterSnapshot = true;
        CaptureResult result = this.pipeline.capture(CHEST, OWNER).join();
        Assertions.assertInstanceOf(CaptureResult.ChangedMeanwhile.class, result);
        Assertions.assertTrue(this.access.removed.isEmpty());
        Assertions.assertTrue(this.store.vaults.isEmpty());
        Assertions.assertEquals(List.of("created", "deleted"), this.events);
    }

    @Test
    void aFailedSaveLeavesTheBlockInPlace() {
        this.store.failCreate = true;
        CaptureResult result = this.pipeline.capture(CHEST, OWNER).join();
        Assertions.assertInstanceOf(CaptureResult.Failed.class, result);
        Assertions.assertTrue(this.access.removed.isEmpty());
        Assertions.assertTrue(this.events.isEmpty());
    }

    @Test
    void aVaultThatCannotBeTakenBackIsReportedAsAFailureAndTheBlockStays() {
        this.access.changesAfterSnapshot = true;
        this.store.failDelete = true;
        CaptureResult result = this.pipeline.capture(CHEST, OWNER).join();
        Assertions.assertInstanceOf(CaptureResult.Failed.class, result);
        Assertions.assertTrue(this.access.removed.isEmpty());
    }

    @Test
    void aStaleCopyInAVaultSomeoneHoldsIsReportedAsAFailure() {
        this.access.changesAfterSnapshot = true;
        this.store.inUse = true;
        CaptureResult result = this.pipeline.capture(CHEST, OWNER).join();
        Assertions.assertInstanceOf(CaptureResult.Failed.class, result);
        Assertions.assertTrue(this.access.removed.isEmpty());
    }

    @Test
    void theFutureNeverCompletesExceptionally() {
        this.store.failCreate = true;
        Assertions.assertFalse(this.pipeline.capture(CHEST, OWNER).isCompletedExceptionally());
    }
}
