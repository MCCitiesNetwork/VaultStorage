# Capture, Placement and Auto-vault Implementation Plan

**Goal:** No path destroys a block or entity before its items are committed, none fails silently, and region checks use real shapes.
**Spec:** `docs/superpowers/specs/2026-11-12-capture-placement-autovault-design.md`
**Status:** executed. Where the code differs from a task, the spec's "As built" section says how.

Conventions: `P` is `plugin/src/main/java/com/minecraftcitiesnetwork/vaultstorage`, `T` the
matching test directory. Branch `refactor/capture-placement` from `refactor/persistence`. Every
task ends with `./gradlew build` green and a commit. Each task leaves the plugin working: old
and new code paths never both handle the same click.

The tasks are ordered so that the ones protecting items come first.

## Task 1: Exact region lookups

**Files:** modify `P/region/WorldGuardService.java`, `P/region/WorldGuardServiceImp.java`; test `T/region/RegionGeometryTest.java` is not needed, the change is a delegation to WorldGuard.

- [x] `getRegionsAt(Block)` asks `RegionManager.getApplicableRegions(BlockVector3)` and builds a `VaultRegion` for each region returned, instead of building every region in the world and filtering by bounding box. `getRegionById` returns null when there is no such region instead of throwing.
- [x] Verified on a live server only: a polygon region no longer covers the corners of its bounding box. Commit: `fix: resolve regions at a block by their real shape`.

## Task 2: The capture pipeline

**Files:** create `P/capture/ContainerAccess.java`, `P/capture/ContainerSnapshot.java`, `P/capture/CaptureResult.java`, `P/capture/CapturePipeline.java`, `P/capture/BukkitContainerAccess.java`, `T/capture/CapturePipelineTest.java`

**Interfaces:**
```java
public record ContainerSnapshot(@NotNull UUID worldUuid, @NotNull String worldName, int x, int y, int z,
                                @NotNull String material, @Nullable String blockData, @NotNull List<VaultItem> items) {}
public interface ContainerAccess {                       // main thread only
    @NotNull ContainerSnapshot snapshot(@NotNull Block block);
    boolean stillMatches(@NotNull Block block, @NotNull ContainerSnapshot snapshot);
    void remove(@NotNull Block block);                  // clears, sets to air
}
public sealed interface CaptureResult {
    record Vaulted(@NotNull UUID vault) implements CaptureResult {}
    record ChangedMeanwhile() implements CaptureResult {}
    record Failed(@NotNull Throwable cause) implements CaptureResult {}
}
public final class CapturePipeline {
    public CapturePipeline(VaultRepository, ContainerAccess, Executor database, Executor main, Logger);
    public @NotNull CompletableFuture<CaptureResult> capture(@NotNull Block block, @NotNull UUID owner);
}
```
`capture` is the spec's capture column: snapshot on the calling (main) thread, `create` on the
database executor, back on the main executor check `stillMatches` and `remove`, or `delete` the
vault and answer `ChangedMeanwhile`. It never completes exceptionally.

- [x] Tests, with a fake `ContainerAccess`, the real `VaultRepository` against Testcontainers, and `Runnable::run` for both executors: a successful capture stores the items under their real slots and removes the block after the vault exists; a container changed in between yields `ChangedMeanwhile`, the block stays and no vault remains; a repository that throws yields `Failed` and the block stays.
- [x] Implement. `BukkitContainerAccess` reads `Chest#getBlockInventory` for chests and `Container#getInventory` otherwise, serializes each non-empty slot with its index, and compares by slot, amount and bytes.
- [x] Commit: `feat: a capture pipeline that saves before it removes`.

## Task 3: Route every capture through the pipeline

**Files:** modify `P/capture/VaultCaptureService.java`, the auto-vault module's `AutoVaultService`

- [x] In `VaultCaptureService`, the three container paths (interactive, direct, offline) stop calling `captureFromBlock` followed by `createVault` and `putItems`. Policy, double-chest handling, Bolt and ChestShop steps stay where they are; the block removal and the two database calls are replaced by one `CapturePipeline.capture`. Protection is removed and the other half re-protected only after `Vaulted`. `busy` is cleared in `whenComplete`.
- [x] The hanging paths persist first (`VaultRepository.appendItems`) and remove the entity after, checking it is still valid; the offline path holds a plugin chunk ticket until then. `HangingVaultSupport.findFirstVaultWithSpace` and the per-owner lock map go.
- [x] Mail moves out of `VaultServiceImpl.createVault` to after a `Vaulted` result.
- [x] Verified on a live server: with the database stopped, a capture leaves the chest and tells the player. Commit: `fix: capture saves the vault before removing the block`.

## Task 4: Sessions end when the player leaves

**Files:** create `P/capture/CaptureSessionListener.java` (or extend the session manager); modify `P/session/VaultSessionManager.java`, `P/VaultStoragePlugin.java`

- [x] One permanent `PlayerQuitEvent` listener calls `VaultSessionManager.end(uuid)`, which already unregisters the dynamic listener and cancels the action bar. Test the manager: after `end`, `get` is null and the mode of a re-created session is `NONE`.
- [x] Commit: `fix: end capture and placement sessions when the player quits`.

## Task 5: Placement claims the vault

**Files:** create `P/placement/PlacementGrant.java`, `P/placement/WorldGuardPlacementGrant.java`, `P/placement/PlacementResult.java`; modify `P/placement/VaultPlacementService.java`, `P/menu/VaultPlacementMenu.java`, `P/Integrations.java`; test `T/placement/PlacementGrantsTest.java`

- [x] `Integrations` holds a list of `PlacementGrant`s (`addPlacementGrant`, `removePlacementGrant`, `mayPlace(UUID, Block)`), with the WorldGuard grant added by the host. Test: no grant refuses, any one grant allows.
- [x] `VaultPlacementService` follows the placement column: `claim`, place on the main thread, `delete` on success, `release` on failure. `AlreadyClaimed` and `NoSuchVault` are reported to the player. Items go back to the slots they were stored under when the placed block is the same kind of container.
- [x] The menu checks `vaultstorage.action.place` and `Integrations.mayPlace` for both the click and the action bar.
- [x] Startup calls `releaseAllClaims()` and logs each released vault at WARN.
- [x] Commit: `fix: placement claims the vault and honours the place permission`.

## Task 6: The vault inventory

**Files:** create `P/vault/VaultView.java`, `P/vault/VaultInventories.java`; delete `P/vault/VaultInventoryService.java`; modify its callers

- [x] One permanent listener keyed on the `VaultView` holder; edit mode claims on open, saves with `replaceItems` and releases on close; copy mode cancels every click and drag that would put an item into the vault; `onDisable` saves open edit views synchronously.
- [x] Commit: `fix: one editor per vault, saved in one transaction`.

## Task 7: Owner notifications through PlayerNotifications

**Files:** create `P/vault/OwnerNotifier.java`, `P/vault/LoggingOwnerNotifier.java`, module `modules/player-notifications-adapter`; modify `Integrations`, the descriptor, the build; remove Essentials

- [x] As the spec's "Owner notifications" section. Module tests as for the other modules. Commit: `feat: notify owners through PlayerNotifications and drop Essentials`.

## Task 8: Shop owners from Treasury

**Files:** create `P/chestshop/ShopOwner.java`, `P/chestshop/Shop.java`, `P/chestshop/ShopDirectory.java`; modify the `chestshop-adapter` module and `VaultCapturePolicy`

- [x] As the spec's "ChestShop" section, including the invalid-owner rule. Policy tests cover every `Reason`. Commit: `feat: resolve shop owners from Treasury's registry`.

## Task 9: GriefPrevention placement

**Files:** module `modules/griefprevention-adapter`

- [x] As the spec's "GriefPrevention" section. Commit: `feat: place vaults on GriefPrevention claims`.

## Task 10: Stored sweeps

**Files:** the `realty-autovault` module: `PendingSweepMapper`, `MariaPendingSweepMapper`, `V1__pending_sweep.sql`, its migrator; modify `AutoVaultService`

- [x] As the spec's "Auto-vault" section. Integration test in the module against MariaDB. Commit: `feat: keep pending sweeps across restarts`.

## Task 11: Scans, the vault list, events, and the end of the adapter

**Files:** modify `P/scan/*`, `P/menu/VaultListMenu.java`, the event classes; delete `P/database/legacy/`

- [x] As the spec's "Scans and the vault list" and "Events and deletions" sections. Update `README.md` and `CLAUDE.md`. Run the boot check and the spec's manual list. Commit: `refactor: page the vault list and remove the legacy service`.
