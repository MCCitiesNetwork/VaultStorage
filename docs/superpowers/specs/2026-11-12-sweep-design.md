# Sweep — design

Date: 2026-11-12
Status: implemented, with the limits listed at the end; the capture service was split afterwards
Step 7 of 7. Depends on step 6.

## Goal

The classes that steps 1 to 6 only moved meet the same rules as the ones they rewrote, and
nothing is left that no code reaches.

## What changed

- **No static singleton.** `VaultStoragePlugin.getInstance()` is gone. Services, the capture
  policy, the region scanner and the root menu take the plugin in their constructor; a child
  menu takes it from its parent. `Text` and `VaultCapturePolicy` are instances the plugin owns.
  `VaultSessionManager` takes only the one thing it needs, a way to register a listener, so it
  is built in tests without a server.
- **Dead code removed.** The old domain model (`Vault`, `VaultImp`, `VaultDto`, `ItemDto`,
  `LocationDto`, their implementations, `Dto`, `DTOConvertible`) fed only the events and a
  session field nothing read. `SearchStrategy`, the unused `AutoDialog` button kinds, the child
  menu list, four unused `BoltService` queries, `VaultRepository.addItems` and three
  `DynamicListener` methods had no caller.
- **Events carry values.** `PlayerVaultEvent` has `vault()`, `owner()` and `location()`;
  `PlayerPlaceVaultEvent` has `location()`; `PlayerDeleteVaultEvent` has `vault()`. All have
  `player()`.
- **Accessors without `get`** on the plugin (`bolt()`, `worldGuard()`, `captureService()`, …),
  the menus (`player()`, `dialog()`, `parentMenu()`) and sessions (`mode()`).
- **Nullability annotations** on parameters and return types of the old classes. They were added
  by a script (nullable where the method checks the value for null or returns null) and then
  read through; the ones it got wrong were corrected by hand.
- **Nothing swallowed.** A Bolt protection that cannot be removed is logged at WARNING.
- **Concrete classes are final.**

## Testing

The 116 unit and database tests and the 23 live specs pass unchanged, apart from the one test of
the removed `addItems`. The sweep adds no behaviour, so it adds no tests.

## What the sweep did not do

- **`CaptureSession.onInteract` is still one method of about 170 lines.** The capture service was
  split by moving code: `CaptureSession` (the per-player listener), `CaptureActionBar`,
  `CaptureOwners`, `ContainerBlocks`, and `DoubleChestRule`, which is the one part that was
  rewritten and has its own test. The click handler's ChestShop branches have no live test, so
  they were moved as they were, not restructured.
- **Menus and services hold the whole plugin**, not the two or three things each uses. That
  removed the singleton without rewriting every constructor chain; narrowing them is possible
  class by class.
- **Queries with an argument keep their names** (`BoltService.getOwner(block)`,
  `WorldGuardService.getRegionsAt(block)`). The accessor rule is about getters.
- **`Imp` and `Impl` suffixes stay.**
- **Annotations are documentation, not checks.** Nothing verifies them at build time.
