# VaultStorage

A Paper plugin that stores Bolt-locked containers as "vaults" in a database when a region
changes hands, and lets the owner place them back.

## Build

```bash
./gradlew build                 # everything: compile, Checkstyle, tests, shaded jars
./gradlew test                  # every project's tests
./gradlew :plugin:test          # the host only; the database tests need a running Docker daemon
./gradlew :plugin:runServer     # a Paper 26.3 test server with the module jars installed
./gradlew :plugin:plugwrightTest   # live-server tests: starts the dev database and a Paper 26.3 server, runs bots
./gradlew :plugin:plugwrightTestLocal -Pspecs=sweep,scan   # only the spec files whose names contain one of these
./gradlew :plugin:plugwrightRestartTest   # the live tests that stop and start the server in between
```

Java 25, Paper 26.3, Gradle 9.8.2. Every version lives in `gradle/libs.versions.toml`; the
project version lives in `gradle.properties`.

## Projects

| Project | Jar | What it is |
| --- | --- | --- |
| `:plugin` | `VaultStorage-<version>.jar` | The host plugin |
| `:modules:namebook-adapter` | `namebook-adapter-<version>.jar` | Names through Namebook |
| `:modules:floodgate-adapter` | `floodgate-adapter-<version>.jar` | Bedrock names, and the menus as Bedrock forms |
| `:modules:chestshop-adapter` | `chestshop-adapter-<version>.jar` | Everything about shops: ChestShop's signs, the rule for whose a shop is, shop owners from Treasury |
| `:modules:realty-adapter` | `realty-adapter-<version>.jar` | Auto-vault on a Realty occupant change |
| `:modules:griefprevention-adapter` | `griefprevention-adapter-<version>.jar` | Placing vaults on GriefPrevention claims |
| `:modules:player-notifications-adapter` | `player-notifications-adapter-<version>.jar` | Telling owners about new vaults |

The host goes in `plugins/`; module jars go in `plugins/VaultStorage/modules/`.

`buildSrc` holds three conventions: `vaultstorage-conventions` (every project),
`vaultstorage-shared-relocation` (every project; the one list of relocations) and
`vaultstorage-module-conventions` (modules).

## Host packages

Under `com.minecraftcitiesnetwork.vaultstorage`, grouped by feature:

| Package | Holds |
| --- | --- |
| (root) | `VaultStoragePlugin` (owns and hands out every service), `Integrations` |
| `capture` | `VaultCaptureService` (prepare, store, offline), `CaptureSession` (a player's capture mode), `CaptureActionBar`, `CaptureOwners`, `ContainerBlocks`, `DoubleChestRule`, `CaptureRules` and `CaptureRegions` (who may vault what), the pipeline with its `CaptureStore` and `ContainerAccess`, the policy; the two hooks modules add to, `ContainerAttachments` and `ContainerOwnerSource`, and `ContainerOwnerLookup` (asks and remembers) |
| `placement` | `VaultPlacementService` (the order of a placement), `PlacementStore` and `PlacementWorld` (what it writes to), `PlacementRules` (who may place where) |
| `vault` | vault types, the vault inventory, item serialization, events |
| `scan` | region scan service and scanner |
| `region` | WorldGuard service and region types |
| `bolt` | Bolt service |
| `names` | `NameSource`, the Paper source, `Names` (the lookup host code calls; remembers names), `UniqueIdentifierResolver` |
| `menu` | dialog menus, `AutoDialog`, `MenuView` and `MenuRenderer` (a menu described apart from how it is drawn), `ConfirmMenu`, `RegionScans` (starting a scan: who may, the cooldown), `Paging`, `VaultLabel` |
| `command` | `VaultCommand` (the Brigadier tree), its sealed outcomes, `VaultActionArgument` |
| `permission` | `Permissions`, one constant per node |
| `session` | per-player session state |
| `config` | `ConfigFiles`, `PluginSettings`, `MessageKeys`, `Text`, the 1.x import |
| `database` | MyBatis mappers, `VaultDatabase`, `VaultRepository`, the schema migrator |

`menu` is a layer, not a feature: it holds every screen. Step 6 changed the screens that had
findings and kept the framework (`ParentMenu`, `ChildMenuImp`, `AutoDialog`).

## Rules

- **The host knows Bolt, WorldGuard and WorldEdit. Every other plugin is reached through a
  module.** The host owns the interface; the module supplies the implementation by
  filling a slot in `Integrations`.
- **Host code reads `Integrations` at use time, never once at construction.** Modules start
  after the host's services exist and can stop while it runs.
- **A module's entry class names no type from its optional plugin.** Those types live in one
  separate class per module (`NamebookNameSource`, `FloodgateNames`, `ChestShopSigns`, `TreasuryShopOwners`,
  `RealtyOccupantChangeListener` behind `RealtyBinding`). Each module's `*ModuleTest` enforces
  it by scanning the entry class file. If the entry class named such a type, loading the module
  on a server without that plugin would throw out of the host's `onEnable`.
- **The host has no code about another plugin.** It has hooks named for what the host needs:
  name sources, placement grants, an owner notifier, another way to draw a
  menu (`MenuRenderer`), what is attached to a
  container (`ContainerAttachments`) and who owns a container nobody locked
  (`ContainerOwnerSource`). Everything about ChestShop, Treasury, Realty and the rest, including
  its rules, its wording and its tests, is in that plugin's module. The host's Java does not
  contain the word "shop".
- **A hook that several plugins could fill is a list** (`addPlacementGrant`, `addAttachments`,
  `addOwnerSource`, `addMenuRenderer`), and an empty list is the default: no no-op class and no `isAvailable()`.
  A provider that throws is logged and counted as knowing nothing.
- **One module does not know another.** The realty module's sweep asks the host's hooks for
  unlocked containers with an owner; it does not know they are shops.
- **A module's wording is the module's**: its own `messages.yml` in its data folder, bundled
  under its own name (`realty-messages.yml`). The host's `messages.yml` has no module's sentence.
- **A module fills its slots last.** It builds everything it supplies first: if `initialize`
  throws, its class loader is closed and `shutdown` is never called, so a slot already filled
  would point at dead classes.
- **An optional plugin a module needs is declared in the host's `paper-plugin.yml`** with
  `required: false` and `join-classpath: true`. Module class loaders are parented to the host's.
- **Relocations live only in `vaultstorage-shared-relocation`.** Modules apply it too, so their
  references to plugin-infrastructure land on the host's relocated copy.
- **There is no static way to the plugin.** A class takes what it needs in its constructor, and
  a child menu takes the plugin from its parent. The playbook's `code-style` applies to new
  code; the step 7 spec lists where older classes still fall short of it.
- **`VaultRepository` is the only way to the database.** One method is one unit of work and one
  transaction. It blocks: call it on `VaultStoragePlugin.databaseExecutor()`, never on the main
  thread. Outcomes are sealed result types; only a broken database is an exception.
- **A migration needs two edits:** the `V<n>__*.sql` file under `sql/migrations` and its
  `MigrationStep` in `VaultStorageSchemaMigrator.MIGRATIONS`. Never edit one that has shipped.
  Write it to be re-runnable; MariaDB commits each `ALTER` on its own.
- **A mapper needs two edits:** the `Maria...` interface and its `addMapper` line in
  `VaultDatabase`.
- **Timestamps are UTC.** The JDBC URL's three time zone options are what make that hold on a
  JVM in another zone; `MigrationTest` pins it.
- **Database tests run against `mariadb:10.11` in Testcontainers**, the version production runs.
  They fail, not skip, without Docker.
- **Checkstyle fails the build** on wildcard imports, static imports, unused imports,
  `printStackTrace` and `System.out`.
- **NullAway fails the build** on main code: a value that may be null is annotated `@Nullable`,
  and one that is not annotated is never null. A field a framework sets later is the one place
  for `@SuppressWarnings("NullAway.Init")`, with a comment saying who sets it.
- **Never commit `*.sql` from the repository root.** A production snapshot is kept there for
  testing and is ignored.

## Rules for config and messages

- **Operator files are `database.yml`, `settings.yml`, `messages.yml`** and one `config.yml` per
  module that needs one (`modules/<module-name>/config.yml`). `ConfigFiles` creates each from the bundled default and never writes it
  again; the defaults go to `defaults/` on every start.
- **Settings are a record read through `VaultStoragePlugin.settings()` at use time.** A
  reload swaps it. `database.yml` is read once.
- **No player-facing text in Java.** A sentence is a key in `messages.yml` and a constant in
  `MessageKeys`, named after the key; `MessageKeysTest` fails when either is missing.
- **Runtime strings go in through `MessageContainer.value`** (or `Text.of(key, map)`, which does
  the same). A component goes in through `MessageContainer.markup`. Never parse a runtime string.
- **`Text` (`plugin.text()`) renders a message with its placeholders given as a map.** A class
  that needs `markup` uses the `MessageContainer` (`plugin.messages()`).
- **A module's bundled config has its own file name** (`realty.yml`): its class loader
  asks the host's first, so `config.yml` would find the wrong file.
- **`/vault reload`** parses `settings.yml` and `messages.yml` off the main thread, swaps both
  only if both parsed, then reloads modules whose manifest says `reloadable: true`.

## Rules for commands and permissions

- **`/vault` is one Brigadier tree**, built by `VaultCommand.tree` from a `Handlers` interface, so
  `VaultCommandTreeTest` checks its branches and permissions without a server. `create` supplies
  the handlers that use the plugin. Each branch has a
  `requires`; a handler checks the sender, reads permissions on the main thread, works on the
  database executor and replies on the main thread.
- **A permission is a constant in `Permissions` and an entry in `paper-plugin.yml`** with a
  description and a default. `PermissionsTest` checks both directions.
- **`vaultstorage.admin` is a declared parent, not an override in code.** A new node is added to
  its `children`; `PermissionsTest` fails otherwise. Code checks the node it means with
  `hasPermission` and nothing else.
- **A command's outcome is a sealed interface** switched over for the reply. No exception text
  reaches a player.

## Rules for menus

- **A menu is described once and can be drawn two ways.** A menu implements `build()`, which
  describes it with `AutoDialog.builder()`: title, lines, inputs, buttons. That description is a
  `MenuView`, and the Paper dialog is made from it, so the two cannot differ. `ParentMenuImp.open`
  builds, asks `Integrations.showMenu` first, and shows the dialog if no module drew it. The
  Floodgate module draws it as Bedrock forms (`BedrockForms`, laid out by `FormPlan`): a form of
  buttons, and the inputs asked for when a button that reads them is pressed, because Bedrock
  has no form with both. A menu with no buttons, such as `LoadingMenu`, reaches a Bedrock
  player as chat.
- **A menu builds on each `open()`, never in its constructor**, so a reload shows at once and
  `again -> open()` is the way back to a menu.
- **A button only some players get is added with `permissionButton`**: hidden without the
  permission, and checked again at the click.
- **A button that reads the menu's inputs is added with `submitButton`**, any other with
  `button` or `wideButton`. Only a submit button makes a Bedrock player fill the inputs in.
- **A dialog's answer comes from the client.** A menu lists only what a player may do, and the
  code that does it checks again: `VaultInventoryService.openVirtualInventory` checks the
  action's permission, and `captureDirectAsync` and `captureHanging` check the capture one.
- **Whoever opens a `LoadingMenu` ends it** on every path: replace it with the next dialog, or
  `closeDialog()` before telling the player what went wrong.
- **Anything that cannot be undone goes through `ConfirmMenu`.** Cancel is its first button.
- **A menu that leads to a vault passes the way back**, so Back returns to the same page and search.
- **A sentence with two outcomes is two keys** (`scan.entry-vaultable` / `-not-vaultable`), chosen
  in Java. Never insert "yes" or "no" into one.
- **A list that is paged is sorted first**, by something that does not change between pages,
  and its pages are worked out by `Paging`.
- **An owner's name comes from `plugin.names()`**: `displayName(s)` in a menu, `displayNameNow`
  for text drawn again and again. No class keeps a name cache of its own.

## Standardisation programme

Specs are in `docs/superpowers/specs/`, one per step. Steps 1 to 3 also have a plan in
`docs/superpowers/plans/`; steps 4 to 7 were built from their spec. Where the code differs from
a spec, the spec's "As built" section says how.

| Step | Status | Delivers |
| --- | --- | --- |
| 1. Scaffold, package move, modules | done | this layout |
| 2. Persistence | done | MyBatis on MariaDB, the playbook schema, transactions |
| 3. Capture, placement, auto-vault | done | save before destroy, futures, two more modules, shop owners |
| 4. Config and messages | done | Configurate records, `messages.yml`, reload |
| 5. Commands and permissions | done | Brigadier, `vaultstorage.command.*` |
| 6. Menus | done | vault labels, delete confirmation, loading dialogs that end, whole-sentence keys |
| 7. Sweep | done | no singleton, dead code removed, accessors, annotations, the capture service split |

What step 3 delivered (plan: `docs/superpowers/plans/2026-11-12-capture-placement-autovault.md`):

- Done: region lookups by real shape, in the capture policy, the sweep and scans; `CapturePipeline` (every capture saves before it removes); sessions
  end on quit; placement claims the vault and asks `Integrations.mayPlace`; the vault inventory
  claims while editing and saves in one transaction; the GriefPrevention module.
- Also done: owner notifications through the PlayerNotifications module (Essentials is gone);
  the vault list pages and loads off the main thread; the events have a handler list; the
  admin delete works; `database.legacy` is deleted and every caller uses `VaultRepository`.
- Also done: scans load chunks off the main thread, always answer, and correct their cache
  after a capture instead of rescanning.
- Also done: a shop sign is removed on region rights no longer, only with the override; a
  lock owner's broken id aborts the capture instead of giving the vault to the capturer.
- Also done: pending sweeps are stored in the auto-vault module's own table and resume after a
  restart; a sweep is tracked to its end and tells each previous owner once.
- Also done: a shop container nobody locked belongs to the shop's player, read from Treasury's
  shop registry, when a player captures it.
- Also done: the sweep has a third pass, over the shop registry, for shop containers nobody
  locked: a displaced player's stocked shop is vaulted to them, and an empty one loses its signs.
  Everything in the step 3 plan is done.

## Live-server tests

`./gradlew :plugin:plugwrightTest` starts MariaDB from `compose.dev.yml` (port 3309), provisions a
Paper 26.3 server with Bolt, WorldGuard, WorldEdit, LuckPerms, GriefPrevention, ViaVersion and
ViaBackwards, Realty, Namebook, PlayerNotifications, Floodgate and the four economy plugins,
installs the host and module jars, and runs the specs in `plugin/src/test/e2e/tests/`. It is never
part of `build`. It needs Docker, Node and `gh` signed in to an account that can read the
Realty and hibernia-economy repositories.

- Bots join as 1.21.11 clients; `plugins/test-server.ts` keeps them connected and still.
- Bots do not walk: place them with `standAt`. Click blocks with `rightClick` in `tests/world.ts`,
  which writes the packet itself; mineflayer's `activateBlock` gets bots kicked here.
- Use `minecraft:tp` and `minecraft:item`, so another plugin's command of the same name cannot
  shadow them.
- Bolt answers on the action bar, which a bot's chat log does not hold.
- Click an entity with the "interact at" form of `use_entity` (`mouse: 2`); the plain form does
  not reach this server from a 1.21.11 bot. See `tests/containers.spec.ts`.
- A chest's `type` is from its own point of view: facing north, `left` is the west block.
- Menus are Paper dialogs: `tests/dialogs.ts` reads and clicks them. A loading dialog comes
  between most screens.
- The test server's log is `plugin/src/test/e2e/generated/local/run/logs/latest.log`.
- ChestShop, Treasury, Business and TreasuryAPI are installed by `installE2eEconomy`: from
  `../hibernia-economy/release` when that checkout has one (built there with `./gradlew
  release`; another place with `-PhiberniaEconomyDir=`), otherwise from the `dev-latest`
  release of `ParadauxIO/hibernia-economy`, fetched with `gh`. Treasury will not start without
  all four. Their database is `treasury` on the dev MariaDB.
- Realty, Namebook and PlayerNotifications are the real plugins, each taken from the jars the
  latest successful `main` run of its repository's `Build` workflow keeps on GitHub Actions,
  fetched with `gh` (`jarFromActions` in `plugin/build.gradle.kts`), or from `-PrealtyJar=`,
  `-PnamebookJar=` or `-PplayerNotificationsJar=`. Each has a database of its own on the dev
  MariaDB. Realty needs an economy, so it enables only when the economy plugins are installed.
- Floodgate is downloaded from GeyserMC.
- Downloads are kept in `plugin/build/e2e-downloads` and reused until GitHub has a newer set.
- A spec that needs a shop, a region sale or a notification starts with `requireChestShop()`,
  `requireRealty()` or `requirePlayerNotifications()` (`tests/shops.ts`) and fails when the plugin is missing. No spec passes
  without running.
- In `tests/sweep.spec.ts` staff registers a region with `/realty register`, naming themself
  as authority or landlord: Realty's default is a government account the test server lacks.
  A buyer gets money from the console with `eco give`. The sweep starts one second after
  Realty's event, the delay the test config sets.
- Realty replaces a region's WorldGuard owners and members with the new occupant before it
  fires its event, so the sweep sees the previous occupant as an outsider.
- Add several WorldGuard members with one `/rg addmember`, and wait for the answer that names
  the region: a bot's messages last the whole test, so an earlier region's answer would match.
- A player who is not an operator is made with `ordinary(server, player, nodes)` in
  `tests/players.ts`: exactly those permissions, through LuckPerms.
- `createPlayer({ username })` brings a bot back under a name that left, for "quit and return".
- The console's answer to a command is the return value of `server.execute`, not a log line.
  An answer sent after work off the main thread reaches no remote console, so such a command
  also logs its outcome.
- The action bar is read with `ActionBars` in `tests/vaults.ts`; turn the bot with
  `lookAtBlock` first, so the server knows what it looks at.
- A region that is not a box is drawn with `definePolygon` in `tests/realty.ts`.
- GriefPrevention allows three players from one address by default and every bot shares one;
  the test server's config lifts that.
- To run a few specs: `./gradlew :plugin:plugwrightTestLocal -Pspecs=capture-edges,sweep` runs
  the spec files whose names contain one of those. `plugwrightTest` takes no filter.
- A test run starts by emptying `Vault`, `VaultItem` and `PendingSweep` (`resetE2eData`): bots get
  a random four-character name, and a name seen before would bring its old vaults.
- To make the world change in the middle of a capture or a placement, hold its database step
  open with `holdVaultTables(seconds)` in `tests/database.ts`, change the world, and wait.
- The restart test is two spec files and a task, `plugwrightRestartTest`, that runs one, then
  the other without wiping the server's folder or the database in between. What the halves
  share is in `tests/restart.ts`: importing a spec file would run its tests.
- Other dimensions are not flat: `netherPlatform` and `standInNether` in `tests/world.ts`.
- A vault's row exists a tick before its block is removed. Wait for the message that follows
  the removal, not for the row, before asserting on the block.
- A bot cannot sneak, so it cannot put a sign on a chest. `shop` in `tests/shops.ts`
  places the sign on stone, turns the stone into the chest, then sends the sign text.

### What the live suite covers

One spec file per area, in `plugin/src/test/e2e/tests/`. A new behaviour gets a test in the
file for its area; a bug found by hand gets a test before its fix.

| Spec | Covers |
| --- | --- |
| `plugin` | the plugin enabling; `/vault` opening and being refused; the admin parent permission |
| `commands` | every branch of `/vault` from a player and from the console: who may run it, bad ids, unknown names, unknown and forbidden actions |
| `security` | answers a client could forge: an action the player may not use, a nonsense action, a permission lost after the menu opened |
| `capture-place` | a chest and a furnace captured and placed back slot for slot; a capture during a database outage |
| `capture-edges` | cancelling; empty, unlocked and non-container blocks; nine container types; enchantments, names and nested contents; a full chest; double chest order; paintings and frames; two players or three clicks at once; quitting in capture mode |
| `containers` | one half of a double chest; an item frame |
| `placement-edges` | a target that is not empty; cancelling; facing and not joining a neighbour; the clicked face; the lock going to the owner; the way back afterwards; empty, unknown-block and overfull vaults; two placers at once; a vault being edited |
| `vault-inventory`, `inventory-edges` | view, edit and copy; putting items in; emptying; an editor who disconnects; delete and transfer while a vault is open |
| `vault-list`, `menus` | paging; list lines; search by name, owner id and vault id; Back from every screen; the page after a delete; page size on reload; a settings file that does not parse |
| `admin` | delete with its confirmation, transfer, open by id |
| `scan`, `scan-edges` | who may scan what; only what the player may vault is listed; the capture permission on a result; the cooldown; region search; overlapping regions at "Here"; paging; filters; teleport; a stale list |
| `policy`, `rules` | members, owners, outsiders and the override; a player's own container; overlapping, touching, stacked and nested regions; WorldGuard parents; signs; an empty frame; an offline heir; GriefPrevention trust |
| `shapes` | an L-shaped region in capture, scan, "Here" and the sweep: its bounding box does not count |
| `chestshop` | a shop captured by its sign; an empty shop; a shop nobody locked |
| `sweep`, `sweep-edges` | a sale, a transfer, a rental and a replaced tenant through Realty; shops; stacked and overlapping regions; an offline occupant; a buyer who leaves; unloaded chunks; several owners; frames; empty and double chests; the module off, reworded and silent |
| `notifications` | an online owner told at once, an offline one on return, a displaced occupant told once with a count, all through PlayerNotifications |
| `outage` | the database down during a placement, a list, an open, an edit being closed and a delete; nothing lost, nothing stuck in use afterwards |
| `actionbar` | what capture and placement mode say about the block looked at, and why not |
| `reload` | a reworded message; a file that does not parse |
| `timing` | the world changing while a capture or a placement is in its database step: a chest gaining, losing an item or being broken, an item frame's item swapped, a block put at the target, repeated clicks |
| `worlds` | a chest vaulted in the nether and placed in the overworld; an overworld region not applying in the nether; a vault from a world the server no longer has |
| `names` | Namebook and Floodgate each taking over at start-up; an offline player found by name; names in any case; a Java player not taken for a Bedrock one |
| `restart-before`, `restart-after` | across a real stop and start of the server: a waiting sweep resumes and runs, a stale claim is released and logged, a stored vault keeps its items. Run by `./gradlew :plugin:plugwrightRestartTest`, not by the ordinary run |
| `zz-scale` | lists over 5,000 vaults of one owner and 35,000 in the table opening within three seconds; tick time while they do |

Not covered live, and why:

- **A Bedrock player.** A bot cannot join as one, so the forms a Bedrock player gets, the chat
  line in place of the loading dialog, and gamertag lookups are untested live. `FormPlanTest`
  covers how a menu is laid out as forms. To look at them by hand, run the test server with
  Geyser and join from a Bedrock client: `./gradlew :plugin:plugwrightRunServerLocal -PwithGeyser`.
- **An edit open when the server stops.** The runner disconnects its bots before it stops the
  server, and a disconnect saves the edit by the ordinary path.

Helpers: `vaults.ts` (vaults, locks, capture mode, placing, entities, the action bar),
`world.ts` (clicks, block and item assertions), `players.ts` (exact permissions), `realty.ts`
(regions changing hands, polygons), `shops.ts` (shops, required plugins), `files.ts` (editing a
config and reloading), `database.ts`, `dialogs.ts`.

## Rules for the capture and placement code

- **A container's owner is its Bolt owner; failing that, whoever a module's `ContainerOwnerSource`
  names.** The ChestShop module names the player its shop signs belong to
  (`ShopOwners.playerOwner`, in the module: every sign in the registry, all one player's). A
  firm's, an admin shop's or an unlisted shop's container has no owner. A source may read a
  database, so `ContainerOwnerLookup.ownerOf` is asked first and `VaultCapturePolicy` uses what
  it remembered.
- **The sweep has three passes:** Bolt-locked blocks, locked hanging entities, and containers
  nobody locked that a module names an owner for (`AutoVaultService.vaultUnlocked`). A container
  with a Bolt owner belongs to the first pass only. All three ask the same `SweepFilter`.
- **Who may vault a container is `CaptureRules.decide`**, a pure function with a test for every
  outcome. `VaultCapturePolicy` only gathers its inputs from Bolt and WorldGuard.
- **A region counts by its real shape.** Ask `WorldGuardService.getRegionsAt` or `isInside`;
  `getRegionsIn` builds every region and is for listing them. A bounding box is used only to
  ask Bolt for candidates and to decide "nested inside" (`RegionGeometry.nestedInside`: inside
  and not the same box, so two regions with equal bounds are nested in neither).
- **Turning regions into the rule's input is `CaptureRegions.at`.** `CaptureRegionsTest` covers
  regions that overlap, touch, nest and lie above one another.
- **Only the override removes what is attached to a container that cannot be vaulted**
  (`CaptureRules.mayRemoveAttachments`).
- **A placement is `VaultPlacementService` over a `PlacementStore` and a `PlacementWorld`**, so
  its order is tested with fakes. Who may place where is `PlacementRules.decide`.
- **A hanging entity goes through `CapturePipeline` too**, as `HangingAccess`. What differs is
  where it is stored, a `CaptureStore`: a container gets a vault of its own (`inNewVault`), an
  item frame or painting goes into the owner's oldest vault with room (`inVaultWithRoom`). Each
  store knows how to take back what it stored.
- **A chunk is held with `ChunkHolds` from store to remove** when nothing else keeps it loaded.
  It counts holders: a plugin chunk ticket is one per chunk.
- **What a capture does with a block is `DoubleChestRule.decide`**: unprotect it, refuse because
  the other half must go first, or capture. It names no Bukkit type and has its own test.
- **Nothing removes a container or a hanging entity except `CapturePipeline`**, through its
  `ContainerAccess.remove`. The order is: read, store, check it is unchanged, remove.
- **Placement claims before it places and deletes after.** A vault is never deleted before its
  block exists.
- **Anything that removes or moves a vault claims it first**, delete included: a vault being
  edited or placed is "in use" to everyone else.
- **A claim is a `Claim`: the vault and a token** stored on its row (`claimToken`). A vault is
  in use exactly while that column is not null; there is no `status` column. Only the
  claim it was taken with releases a vault, saves an edit to it or deletes it, so a path that
  never got the claim, or that held it before a restart, cannot touch someone else's. Whoever
  claims keeps the `Claim` it was given. Nothing deletes a vault by its id alone.
- **A capture holds the vault it creates** (`createClaimed`) until it has removed the container,
  then lets go through `ClaimReleases`. A vault made for item frames is not held, so the next
  frame joins it; undoing such an append leaves a vault someone has opened meanwhile.
- **A transfer does not move a vault someone holds**: `setOwner` answers `InUse`.
- **A claim is given back through `ClaimReleases`**, never `VaultRepository.release` directly: it
  retries while the database is down, so an outage does not leave a vault in use until a restart.
- **A sweep captures both halves of a double chest**, the half with items first. Bolt keeps one
  lock for the pair, so the scan finds it once.
- **A scan lists what the policy lets the player vault.** The owner filter applies only when
  someone is looking at one other player's things.
- **Editing a vault claims it.** Every claim is released: on close, on a failed open, at
  shutdown, and for all vaults at startup.
- **One server per database.** Startup releases every claim in the table, so a second server
  on the same database would release the first one's. Tokens keep that from losing an edit,
  but a placement under way would leave both its block and its vault.
- **A module that owns a table** keeps its own migrator and version table, registers its mapper
  with `VaultDatabase.registerMapper`, and reads it through `SqlSessionWrapper.mapper`. The
  auto-vault module's `PendingSweepStore` is the example.
- **Where a player may place is `Integrations.mayPlace`.** A module adds a `PlacementGrant`; a
  grant that throws counts as no.
