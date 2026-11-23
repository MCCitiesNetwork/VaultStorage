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

- **Menus, the capture service and the scan classes hold the whole plugin.** A menu takes it
  from its parent, and each of those classes uses most of what the plugin hands out.
  `VaultPlacementService`, `VaultCapturePolicy`, `CaptureOwners` and `BoltServiceImp` now take
  only what they use.
- **Queries with an argument keep their names** (`BoltService.getOwner(block)`,
  `WorldGuardService.getRegionsAt(block)`). The accessor rule is about getters.
- **`Imp` and `Impl` suffixes stay.**
- **Null checks on `plugin.bolt()` and `plugin.worldGuard()` remain** although neither is null
  once the plugin has enabled.

## Done after the sweep

- **`CaptureSession.capture` is five short methods.** Every click ends in one place, `finish`,
  which clears the busy flag and reports a failure.
- **Annotations are checked at build time** by NullAway, on main code. Turning it on found 42
  places where an annotation or a missing one was wrong; all are corrected.

