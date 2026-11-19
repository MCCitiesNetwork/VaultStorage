# Capture, placement and auto-vault — design

Date: 2026-11-12
Status: implemented; see "As built" for where the code differs
Step 3 of 7. Depends on step 2 (`2026-11-12-persistence-design.md`).

## Goal

No path may destroy a block or entity before its items are committed to the database, and no
path may fail silently. Region checks use the region's real shape, sessions end when the player
leaves, and nothing blocks the main thread.

## Audit findings this step closes

- Capture clears and removes the block, then saves; any failed save loses the items.
- After a failed save the capture session ignores every later click.
- Placement can leave both the block and the vault; two actors can place or edit one vault.
- Editing a vault deletes its rows one by one before re-inserting.
- Capture mode survives logout and is still armed after rejoin.
- Regions are treated as bounding boxes in the capture policy and the sweep.
- Every region lookup rebuilds every region in the world.
- Pending sweeps are lost on restart; a sweep sends one mail per container.
- Essentials is a required dependency, used only to mail the vault owner.
- The admin delete fires a synchronous event from a pool thread and always fails.
- The vault list queries on the main thread and renders every vault.
- Logging out mid-scan blocks that region for that player; scans load chunks synchronously.
- Any region member can remove a ChestShop sign from any empty container, and a shop whose
  container is not Bolt-locked cannot be vaulted at all.
- A zero-UUID Bolt owner hands the vault to the capturing player.
- Captured items are renumbered from slot 0, so a furnace's fuel can come back in the wrong slot.
- The vault list is limited to the current world for admins and not for owners.
- The plugin's events cannot be listened to (no static `getHandlerList()`).

## The ordering rule

Every operation that moves items between the world and the database follows one sequence:

| | Capture (world to database) | Placement (database to world) |
| --- | --- | --- |
| 1. Main thread | Check policy. Snapshot the contents as serialized bytes. Change nothing. | Check permission and that the target is air. |
| 2. Database | `create` the vault and its items in one transaction. | `claim` the vault. `AlreadyClaimed` means someone else has it. |
| 3. Main thread | Check the container still holds exactly the snapshot. If so, clear it, remove the block, remove the Bolt protection. | Check the target is still air. Place the block, fill it, protect it. |
| 4. Database | If step 3 found a change, `delete` the vault just created. | If step 3 succeeded, `delete` the vault. If not, `release` it. |

Why this order: a failure at any step leaves the items in at least one place. The cost is a
crash between steps 2 and 3 of a capture, or 3 and 4 of a placement, which leaves the items in
both. Duplication after a crash was chosen over loss after a database error, because database
errors are routine and crashes inside a window of a few milliseconds are not.

Rejected: removing first and restoring the block if the save fails. It keeps today's failure
mode for crashes and for restores that themselves fail.

On startup `releaseAllClaims()` runs and logs each released vault at WARN. A claim can only be
left behind by a crash, and after a crash the placed block has usually not been saved either.

A capture stores each item under the slot it occupied in the container, and a placement puts
it back in that slot when the block placed is the same kind of container. Today items are
renumbered from slot 0, which moves a furnace's fuel or a brewing stand's ingredient. Vaults
captured before this step keep the compacted numbering they were saved with.

Item frames and paintings follow the capture column, with "the entity is still valid and shows
the same item" as the step 3 check.

## Architecture

All classes take their collaborators and two `Executor`s (database and main thread) through the
constructor. None of the rewritten classes calls `VaultStoragePlugin.getInstance()`.
Asynchronous work is a `CompletableFuture` chain: database work on the database executor, an
explicit hop to the main-thread executor before any Bukkit call, and an `exceptionally` at the
end that logs through the plugin logger and tells the player.

### Capture

| Type | Responsibility |
| --- | --- |
| `ContainerAccess` | Interface over the world: `snapshot(block)`, `stillMatches(block, snapshot)`, `remove(block)`, and the same three for a hanging entity. The Bukkit implementation is the only code that touches blocks and entities. |
| `CaptureService` | Runs the capture column of the ordering rule against `ContainerAccess` and `VaultRepository`. Returns a sealed `CaptureResult`: `Vaulted`, `ProtectionRemoved`, `NotAllowed`, `ChangedMeanwhile`, `InvalidOwner`, `Failed`. Used by the interactive session, the scan menu and the sweep. |
| `CaptureSessions` | One permanent listener for `PlayerInteractEvent`, `PlayerInteractEntityEvent` and `PlayerQuitEvent`, and a map of player UUID to session state. Replaces the per-player `DynamicListener`. Quit removes the session. The busy flag is cleared in `whenComplete`, so it is cleared on failure too. |
| `VaultCapturePolicy` | Same rules, with the two changes below. Takes region participation as input, so it is tested without WorldGuard. |

The double-chest, sign and ChestShop branches of `VaultCaptureService` keep their behaviour and
move into `CaptureService`.

Policy changes:

- **A shop is owned by its shop owner.** See "ChestShop" below. This replaces the special rule
  that let any region member remove a shop sign from any empty container.
- **An invalid (all-zero) Bolt owner aborts the capture** with `InvalidOwner` and a log line. It
  is no longer resolved from the capturing player's name.

Telling the owner: one notification per interactive capture, sent after the commit. See
"Owner notifications" below.

### Placement and the vault inventory

`PlacementService` runs the placement column. It checks `vaultstorage.action.place` as well as
where the player may place; the node exists today and nothing checks it.

Where a player may place is no longer WorldGuard alone. A displaced player who has lost their
only region can today retrieve nothing, because placing requires belonging to the region at
that spot. Placement is allowed when the player has the override permission or when any
`PlacementGrant` says yes:

| Type | Where | Says yes when |
| --- | --- | --- |
| `PlacementGrant` | host, package `placement` | Interface: `boolean mayPlace(UUID player, Block target)`. Main thread. A list in `Integrations`, not a single slot. |
| `WorldGuardPlacementGrant` | host | The player is an owner or member of a WorldGuard region at the target. Today's rule. |
| `GriefPreventionPlacementGrant` | module `griefprevention-adapter` | The target is inside a GriefPrevention claim where GriefPrevention lets the player build. |

The same grants drive the "Placeable: yes / no" action bar.

`VaultInventories` replaces `VaultInventoryService`:

- The inventory's `InventoryHolder` is a `VaultView(vaultUuid, action)`, and one permanent
  listener handles every open vault. No listener is registered per open.
- Edit mode `claim`s the vault on open and `release`s it after saving on close, so two people
  cannot edit, or edit and place, the same vault. View and copy do not claim.
- Saving is `replaceItems`, one transaction.
- Copy mode cancels every click and drag that would put an item into the vault's inventory.
  Today shift-clicks and hotbar swaps move the player's own items in, where they vanish.
- `onDisable` saves every open edit view synchronously before the pool shuts down.

### Regions

`WorldGuardService.regionsAt(block)` uses `RegionManager.getApplicableRegions`, which is
spatially indexed and respects polygon shapes. It replaces building every region in the world
and filtering by bounding box. `regionsIn(world)` remains for the region list menu only.

`SweepFilter` asks for the regions at each candidate's position instead of classifying by box.
A candidate is vaulted when the swept region is among them, its owner is not a participant of
the swept region or the new occupant, no region there is nested inside the swept region, and its
owner participates in no other region there. Those are today's rules with exact shapes.
"Nested" is still decided by bounding-box containment, as `RegionGeometry` does now.

### ChestShop (module `chestshop-adapter`)

Today VaultStorage knows a shop only as "a sign that ChestShop says is valid", and decides
everything from the container's Bolt owner. That fails in three ways: a shop on an unlocked
container cannot be vaulted, the sweep never finds such shops, and the only rule left for their
signs is that any region member may remove them.

Hibernia's Treasury keeps a registry of every live shop and exposes it as a Bukkit service,
`io.paradaux.treasury.api.ShopQueryApi`. Each `ShopResult` carries the sign's location, the
account type, and for a personal shop the owner's UUID (`shopOwnerUuid`). Treasury fills that
column only for `PERSONAL` shops; `BUSINESS`, `GOVERNMENT` and `SYSTEM` shops have none. So the
owner is read, not inferred, and it is a player only for personal shops.

In the host, package `chestshop`:

| Type | Role |
| --- | --- |
| `ShopOwner` | Sealed: `Player(UUID)`, `Organisation` (firm or government account), `Admin`, `Unknown`. |
| `ShopDirectory` | Interface: `CompletableFuture<List<Shop>> shopsInChunk(world, chunkX, chunkZ)`, where `Shop` is a sign position and a `ShopOwner`. Never completes exceptionally. A new `Integrations` slot; the default returns no shops. |

In the module:

| Type | Role |
| --- | --- |
| `TreasuryShopDirectory` | Calls `ShopQueryApi.shopsInChunk` on the host's database executor, because it reads Treasury's database. The only class that names Treasury's types. |
| `ChestShopServiceImp` | As today: recognises and removes shop signs through the `com.Acrobot.ChestShop` classes, which Hibernia's ChestShop keeps for exactly this purpose and routes into its own removal and registry cleanup. |

The module fills the `ChestShopService` slot when ChestShop is installed and the `ShopDirectory`
slot when Treasury is. Treasury joins the host's descriptor as an optional dependency
(`io.paradaux:treasury-api`, `compileOnly` in the module, the version homeland-security uses).

Rules, when a directory is available:

- **The effective owner of a shop container is its Bolt owner if it has one, otherwise the
  shop's `Player` owner.** The capture policy then applies unchanged. A displaced tenant's
  unlocked, stocked shop is vaulted to that tenant like any other container, and its sign is
  removed.
- **A shop sign on an empty container is removed under the same policy**, with the shop owner as
  the owner. A region member can no longer remove the sign of the region's owner or of another
  member.
- **The sweep asks the directory for each chunk it already visits** and treats shops there as
  candidates alongside Bolt protections, through the same `SweepFilter`.
- **`Organisation`, `Admin` and `Unknown` shops are never vaulted or removed by the sweep or by
  a region participant.** There is no player to own the vault. Someone with the override
  permission may still remove the sign; the container is then handled by its Bolt owner alone.

Without a directory the behaviour is today's, minus the "any member may remove a sign" rule: a
shop sign is removed only when its container is captured or unlocked under the normal policy.

### Auto-vault (module `realty-autovault`)

The module owns the whole feature, including its table.

- **Pending sweeps are stored.** The module keeps its own schema, as the `modules` convention
  allows: version table `vaultstorage_autovault_schema_version`, script
  `V1__pending_sweep.sql`, table `PendingSweep (worldUuid UUID, regionId VARCHAR(255),
  initiatorUuid UUID, dueTime DATETIME(3), PRIMARY KEY (worldUuid, regionId))`. It registers
  its `PendingSweepMapper` on the host's MyBatis configuration and uses the host's pool.
  `submit` upserts the row and schedules the task; the sweep deletes the row when it finishes.
  When the module starts, stored sweeps are rescheduled, overdue ones after five seconds.
- **Each chunk is held with a plugin chunk ticket** while its containers and hangings go through
  the ordering rule, and released afterwards. This is what makes it safe to remove a hanging
  after the save instead of before it.
- **A failed chunk load is counted and logged**, so the pacing task always ends.
- **One notification per previous owner per sweep**, sent when the sweep finishes, stating how
  many containers were vaulted and in which region.

The sweep calls the host's `CaptureService`, `WorldGuardService`, `ShopDirectory` and
`OwnerNotifier`. `SweepFilter` stays in the module.

### Owner notifications (module `player-notifications-adapter`)

Essentials goes. Its only use is `VaultServiceImpl.createVault`, which mails the vault's owner
and reads both players' names from Essentials' user files. PlayerNotifications replaces it.

In the host, package `vault`:

| Type | Role |
| --- | --- |
| `OwnerNotifier` | Interface: `void vaultCreated(UUID owner, UUID actor, String regionId)` and `void sweepVaulted(UUID owner, int containers, String regionId)`. Best effort; never throws. A new `Integrations` slot. |
| `LoggingOwnerNotifier` | The slot's default. Logs at INFO that the owner was not told. |

In the module:

| Type | Role |
| --- | --- |
| `PlayerNotificationsOwnerNotifier` | Registers one renderable data type, `vaultstorage`, and enqueues a `TypedNotification` for the owner, as homeland-security does. The only class that names PlayerNotifications' types. |

PlayerNotifications stores the notification, shows it in the owner's inbox, and delivers it
through whichever media that player chose, which can include Essentials mail through
PlayerNotifications' own module. VaultStorage no longer knows about any of that.

The actor's name in the text comes from `Names`. The message templates stay in the existing
`mail/mail.yml` until step 4 moves all text to `messages.yml`; `MailConfig` is renamed
`NotificationTexts`.

`Essentials` leaves the host's descriptor and the build. `PlayerNotifications` joins the
descriptor as an optional dependency (`io.github.md5sha256:player-notifications-api`,
`compileOnly` in the module, the version homeland-security uses).

### GriefPrevention (module `griefprevention-adapter`)

A new module, so that a player can place a vault on a GriefPrevention claim where they may
build.

| Type | Role |
| --- | --- |
| `GriefPreventionModule` | Entry class. Adds the grant to `Integrations` in `initialize` and removes it in `shutdown`. Names no GriefPrevention type. |
| `GriefPreventionPlacementGrant` | `dataStore.getClaimAt(location, false, null)`, then GriefPrevention's own build check, `claim.checkPermission(player, ClaimPermission.Build, null)`. The only class that names GriefPrevention's types. |

Rules:

- **The rule is GriefPrevention's own.** A player may place a vault wherever GriefPrevention
  would let them place a block: the claim's owner, anyone with build or manage trust, and
  everyone when the claim grants build trust to the public. Container and access trust are not
  enough, because placing a vault places a block.
- **Subdivisions and admin claims follow GriefPrevention's check** rather than rules of our own.
- **No claim, no grant.** Unclaimed land does not allow placement through this module.
- **It affects placement only.** Capture and the sweep stay WorldGuard and Bolt rules; nothing
  is vaulted out of a GriefPrevention claim because of this module.

`GriefPrevention` joins the host's descriptor as an optional dependency
(`com.github.TechFortress:GriefPrevention` 16.18.8, `compileOnly` in the module, the version
GriefPreventionUI builds against). That artifact comes from jitpack, so the repository returns
to the build, filtered to that one group.

### Scans and the vault list

- `VaultRegionScanner` loads chunks with `getChunkAtAsync` and always calls back, with an empty
  result if the player has left. `VaultScanServiceImpl` drops its pending entry in every case.
- After a capture from the scan menu, the captured entry is removed from the cached result
  instead of clearing every player's cache and rescanning the region.
- `VaultListMenu` loads one page of 20 through `VaultRepository.page` on the database executor
  and gets previous and next buttons. Owners and admins both see vaults from every world; the
  admin list is no longer limited to the world the admin is standing in. Its labels and layout are otherwise unchanged; step 6
  redesigns them.

### Events and deletions

- `PlayerDeleteVaultEvent` fires on the main thread before the delete; the result shown to the
  admin is the delete's row count.
- Each event class gets its own `HandlerList` and a static `getHandlerList()`.
- Removed: `VaultServiceImpl` and the `VaultService` interface (callers use `VaultRepository`
  directly), `DynamicListener`, `VaultSessionManager`, `VaultInventoryService`,
  `HangingVaultSupport.findFirstVaultWithSpace`, the per-owner lock map.

## Conventions that apply

`threading` and `code-style` throughout; `modules` for the three new modules and the reworked
auto-vault and ChestShop ones; `database` for the auto-vault module's schema; `testing`. Player-facing text
keeps coming from the existing YAML files and literals until step 4.

## Error handling

- Every future chain ends in `exceptionally`: the cause is logged with the vault or block
  coordinates, the player is told the action failed, and the session is usable again.
- Expected outcomes are `CaptureResult` and `PlacementResult` cases, not exceptions.
- A delete that fails after a successful placement leaves the vault claimed, logs at SEVERE with
  its id, and tells the player the block was placed. The vault cannot be placed a second time.
- After every hop back to the main thread the code checks the player is still online.

## Testing

Unit tests, with a fake `VaultRepository`, a fake `ContainerAccess` and `Runnable::run` for both
executors:

- A failing `create` leaves the container untouched and yields `Failed`.
- A container changed between snapshot and removal yields `ChangedMeanwhile`, and the vault
  created for it is deleted.
- A successful capture removes the block only after `create` returned.
- A placement whose target is no longer air releases the claim and keeps the vault.
- A second placement of a claimed vault is refused.
- Placement is refused with no grant, allowed with any one grant, and allowed with the override
  permission alone.
- A furnace captured and placed back has its input, fuel and output in their original slots.
- The session's busy flag is clear after a failure; a quit removes the session.
- The policy table: every `Reason`, the ChestShop rule, and the invalid-owner case.
- A capture notifies the owner once, after the commit; a sweep of many containers for one owner
  notifies once; a notifier that throws does not fail the capture.
- A shop on an unlocked container is vaulted to its shop owner; an organisation or admin shop is
  left alone; a member cannot remove the sign of a co-member's shop.
- `SweepFilter` with an L-shaped region: a container inside the bounding box and outside the
  shape is spared.

Integration, against MariaDB, in the auto-vault module: its schema applies beside the host's,
and a stored sweep is rescheduled by a fresh module instance.

Manual, on a live server:

1. Stop the database, capture a chest: the chest and its items remain and the player is told.
2. Start the database, capture, place back: contents match, the vault is gone.
3. Log out in capture mode, rejoin, right-click a chest: nothing happens.
4. Two admins open the same vault in edit mode: the second is refused.
5. Trigger a sweep, restart inside the grace period: the sweep runs after the restart.
6. Sweep a polygon region next to another plot: the neighbour's containers are untouched.
7. A player with no WorldGuard region places a vault on their own GriefPrevention claim and on
   one where they have build trust, and is refused where they have only container trust and on
   unclaimed land.
8. Sweep a region holding a previous tenant's unlocked shop, a firm shop and an admin shop:
   only the first is vaulted, and its sign is gone from `/find`.
9. Sweep a region with item frames in unloaded chunks: each ends up in a vault and is gone
   from the world.

## Known limitations

- **Shop owners are read from Treasury, as designed above, with two differences.** The host
  asks through `ShopOwnerLookup`, which remembers an answer for thirty seconds so that the
  synchronous policy and the action bar can use it. And the sweep's shop pass removes the signs
  of a displaced player's empty shop, as a capture by hand does.
- **The action bar says "unprotected" for an unlocked shop for a moment**, until the registry
  has answered; the click itself waits for the answer.

- **A crash inside the ordering rule's windows can duplicate items.** Chosen, as explained above.
- **A container emptied by a hopper between snapshot and removal makes that capture report
  `ChangedMeanwhile`.** The player, or the next sweep, tries again.
- **Firm, government and admin shops stay where they are after a region changes hands.** The
  new occupant needs staff, or the firm, to remove them.
- **The shop registry can lag the world.** A shop Treasury has not yet recorded is treated as
  having no shop owner, so it falls back to its Bolt owner.
- **Player-facing text is still partly hard-coded** until step 4, and the menus other than the
  vault list keep their current design until step 6.
- **A player with neither a WorldGuard region nor a GriefPrevention claim still cannot retrieve
  a vault.** There is no withdraw mode; placing on land where they may build is the only way out.
- **Without the PlayerNotifications module the previous owner is not told** that their
  containers were vaulted. Today Essentials is required, so they always get mail. The log
  records each case.

## As built

Names and shapes that differ from the sections above:

| Spec | Code |
| --- | --- |
| `CaptureService`, `PlacementService`, `VaultInventories` | `VaultCaptureService`, `VaultPlacementService`, `VaultInventoryService` |
| `CaptureSessions`, one permanent listener | `CaptureSession`, one per player in capture mode, registered through `DynamicListener` |
| `DynamicListener` and `VaultSessionManager` removed | kept; `VaultSessionManager` holds each player's mode and action bar |
| `CaptureResult` with six cases | three (`Vaulted`, `ChangedMeanwhile`, `Failed`); "not allowed", "only unlocked" and "owner unknown" are decided before the pipeline, by `CaptureRules`, `DoubleChestRule` and `CaptureOwners` |
| `PlacementResult` | `VaultPlacementService.Result(success, messageKey)`; the place permission is checked by the menu |
| `OwnerNotifier` builds the text; `LoggingOwnerNotifier` | the host's `OwnerNotifications` builds it and `OwnerNotifier.deliver` sends it; with no notifier the host logs |
| `PlacementGrant.mayPlace(player, Block)` | takes a `Location` |
| services take collaborators and two executors | they take the plugin |
| the policy "takes region participation as input" | `CaptureRules.decide` does; `VaultCapturePolicy` gathers it |

Tests named under "Testing" that were not written as unit tests, and what covers the behaviour:

| Promised | Covered by |
| --- | --- |
| placement: target no longer air, a second placement refused, the override alone | not unit-tested; `VaultPlacementService` is bound to Bukkit. Live: placement refused on foreign land, allowed on a claim, and a claimed vault refused to a second editor |
| a furnace round trip | live: a furnace comes back with input, fuel and output in their slots |
| the busy flag clear after a failure | live: the capture after a database outage succeeds |
| a member cannot remove a co-member's shop sign | `CaptureRulesTest` for the capture rule; the sign rule itself (override only) is not tested |
| a sweep rescheduled by a fresh module instance | `PendingSweepStoreTest` at store level only |

A Bolt-locked chest that carries a firm's or an admin shop's sign is vaulted for its Bolt owner:
the Bolt owner decides, as the ChestShop section says, and the sign goes with the container.
