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
```

Java 25, Paper 26.3, Gradle 9.8.2. Every version lives in `gradle/libs.versions.toml`; the
project version lives in `gradle.properties`.

## Projects

| Project | Jar | What it is |
| --- | --- | --- |
| `:plugin` | `VaultStorage-<version>.jar` | The host plugin |
| `:modules:namebook-adapter` | `namebook-adapter-<version>.jar` | Names through Namebook |
| `:modules:floodgate-adapter` | `floodgate-adapter-<version>.jar` | Bedrock names and the Bedrock client check |
| `:modules:chestshop-adapter` | `chestshop-adapter-<version>.jar` | ChestShop sign handling; shop owners from Treasury |
| `:modules:realty` | `realty-<version>.jar` | Auto-vault on a Realty occupant change |
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
| `capture` | `VaultCaptureService` (prepare, store, offline), `CaptureSession` (a player's capture mode), `CaptureActionBar`, `CaptureOwners`, `ContainerBlocks`, `DoubleChestRule`, `CaptureRules` and `CaptureRegions` (who may vault what), the pipeline, the policy |
| `placement` | `VaultPlacementService` (the order of a placement), `PlacementStore` and `PlacementWorld` (what it writes to), `PlacementRules` (who may place where) |
| `vault` | vault types, the vault inventory, item serialization, events |
| `scan` | region scan service and scanner |
| `region` | WorldGuard service and region types |
| `bolt` | Bolt service |
| `shop` | `ShopSigns` (the signs a shop plugin keeps on containers); `ShopDirectory`, `Shop`, `ShopOwner`; `ShopOwners` (the rule) and `ShopOwnerLookup` (asks and remembers). Names no shop plugin |
| `names` | `NameSource`, the Paper source, `Names`, `UniqueIdentifierResolver` |
| `menu` | dialog menus, `AutoDialog`, `ConfirmMenu`, `VaultLabel` |
| `command` | `VaultCommand` (the Brigadier tree), its sealed outcomes, `VaultActionArgument` |
| `permission` | `Permissions`, one constant per node |
| `session` | per-player session state |
| `config` | `ConfigFiles`, `PluginSettings`, `MessageKeys`, `Text`, the 1.x import |
| `database` | MyBatis mappers, `VaultDatabase`, `VaultRepository`, the schema migrator |

`menu` is a layer, not a feature: it holds every screen. Step 6 changed the screens that had
findings and kept the framework (`ParentMenu`, `ChildMenu`, `AutoDialog`).

## Rules

- **The host knows Bolt, WorldGuard and WorldEdit. Every other plugin is reached through a
  module.** The host owns the interface; the module supplies the implementation by
  filling a slot in `Integrations`.
- **Host code reads `Integrations` at use time, never once at construction.** Modules start
  after the host's services exist and can stop while it runs.
- **A module's entry class names no type from its optional plugin.** Those types live in one
  separate class per module (`NamebookNameSource`, `FloodgateNames`, `ChestShopSigns`,
  `TreasuryShopDirectory`,
  `RealtyOccupantChangeListener` behind `RealtyBinding`). Each module's `*ModuleTest` enforces
  it by scanning the entry class file. If the entry class named such a type, loading the module
  on a server without that plugin would throw out of the host's `onEnable`.
- **An integration is a host concept, never a plugin's name.** The host has shop signs, name
  sources, placement grants and an owner notifier; it has no `chestshop` or `realty` package.
- **A slot that several plugins could fill is a list** (`addPlacementGrant`, `addShopSigns`), and
  an empty list is the default: no no-op class and no `isAvailable()`. A provider that throws is
  logged and counted as having nothing to say.
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

- **Whoever opens a `LoadingMenu` ends it** on every path: replace it with the next dialog, or
  `closeDialog()` before telling the player what went wrong.
- **Anything that cannot be undone goes through `ConfirmMenu`.** Cancel is its first button.
- **A menu that leads to a vault passes the way back**, so Back returns to the same page and search.
- **A sentence with two outcomes is two keys** (`scan.entry-vaultable` / `-not-vaultable`), chosen
  in Java. Never insert "yes" or "no" into one.
- **A list that is paged is sorted first**, by something that does not change between pages.

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
ViaBackwards, Realty and the four economy plugins,
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
- Realty is the real plugin, installed by `installE2eRealty` from the jars the latest
  successful `main` build of `MCCitiesNetwork/realty` keeps on GitHub Actions, fetched with
  `gh` (or `-PrealtyJar=<file>`). Its database is `realty` on the dev MariaDB. It needs an
  economy, so it enables only when the economy plugins are installed.
- Downloads are kept in `plugin/build/e2e-downloads` and reused until GitHub has a newer set.
- A spec that needs a shop or a region sale starts with `requireChestShop()` or
  `requireRealty()` (`tests/shops.ts`) and fails when the plugin is missing. No spec passes
  without running.
- In `tests/sweep.spec.ts` staff registers a region with `/realty register`, naming themself
  as authority or landlord: Realty's default is a government account the test server lacks.
  A buyer gets money from the console with `eco give`. The sweep starts one second after
  Realty's event, the delay the test config sets.
- Realty replaces a region's WorldGuard owners and members with the new occupant before it
  fires its event, so the sweep sees the previous occupant as an outsider.
- Add several WorldGuard members with one `/rg addmember`, and wait for the answer that names
  the region: a bot's messages last the whole test, so an earlier region's answer would match.
- A test run starts by emptying `Vault`, `VaultItem` and `PendingSweep` (`resetE2eData`): bots get
  a random four-character name, and a name seen before would bring its old vaults.
- A vault's row exists a tick before its block is removed. Wait for the message that follows
  the removal, not for the row, before asserting on the block.
- A bot cannot sneak, so it cannot put a sign on a chest. `shop` in `tests/shops.ts`
  places the sign on stone, turns the stone into the chest, then sends the sign text.

Covered live: `/vault` opening and being refused; capturing a locked chest, finding it in the
menus and placing it back with items in their slots; a furnace coming back with its input, fuel and
output where they were; a capture during a database outage leaving
the chest untouched and working again afterwards; viewing a vault without being able to take from
it; editing a vault and the change being saved; a second editor being refused until the first
closes; paging through 45 vaults; an admin searching by owner name, deleting a vault,
transferring one and opening one by id; scanning a WorldGuard region, vaulting a result and
seeing it leave the list.

Also covered live, with players who are not operators (permissions through LuckPerms): a region
member vaulting a non-member's chest; a non-member, a co-member's chest and a chest outside any
region all being refused; placement refused on land the owner has no part in and allowed on
their own GriefPrevention claim.

Also covered live: one half of a double chest being vaulted and the other staying locked for its
owner; a locked item frame being vaulted with the item it shows. The same frame found by a
region scan is vaulted from the results, not merely unlocked.

Also covered live: the action menu naming the vault's block and position; delete asking first,
Cancel keeping the vault and Delete removing it.

Also covered live: `vaultstorage.admin` alone giving a non-operator the staff commands, and a
player with only the menu permission being refused `/vault reload`.

Also covered live: `/vault reload` picking up a reworded message and refusing a file that does not
parse.

Also covered live, when a `hibernia-economy` checkout is beside this repository: capturing a
locked shop by its sign vaults the container, and an empty locked shop loses its lock and its
sign; in both, ChestShop's registry marks the shop inactive. A stocked shop nobody locked is
vaulted to the shop's owner, not to the player who clicked.

Also covered live, through Realty itself: a region being bought vaults the previous occupant's
locked chest for them and tells the buyer once; the buyer's own locked chest stays. The same
sweep after a title transfer, a rental of a vacant region, and a tenant replaced by staff. The
sweep vaults the previous occupant's stocked unlocked shop, removes the sign of their empty one
and leaves the chest, and leaves the buyer's own shop alone.

Also covered live, for regions that share space: where two regions overlap, a member of one
vaults an outsider's chest and not the other region's member's; a member of a region next
door, or of the region above, has no say; a member of a city has none over a plot inside it.
Buying the region above another leaves a chest in the region below, and buying a region that
overlaps another leaves that region's member's chest.

Shared steps are in `tests/vaults.ts`.

## Rules for the capture and placement code

- **A container's owner is its Bolt owner; failing that, the player its shop signs belong to**
  (`ShopOwners.playerOwner`: every sign in the registry, all one player's). A firm's, an admin
  shop's or an unlisted shop's container has no owner. The registry is a database, so
  `ShopOwnerLookup.ownerOf` is asked first and `VaultCapturePolicy` uses what it remembered.
- **The sweep has three passes:** Bolt-locked blocks, locked hanging entities, and shops whose
  container nobody locked (`AutoVaultService.vaultShops`). A container with a Bolt owner belongs
  to the first pass only. All three ask the same `SweepFilter`.
- **Who may vault a container is `CaptureRules.decide`**, a pure function with a test for every
  outcome. `VaultCapturePolicy` only gathers its inputs from Bolt and WorldGuard.
- **A region counts by its real shape.** Ask `WorldGuardService.getRegionsAt` or `isInside`;
  `getRegionsIn` builds every region and is for listing them. A bounding box is used only to
  ask Bolt for candidates and to decide "nested inside" (`RegionGeometry.nestedInside`: inside
  and not the same box, so two regions with equal bounds are nested in neither).
- **Turning regions into the rule's input is `CaptureRegions.at`.** `CaptureRegionsTest` covers
  regions that overlap, touch, nest and lie above one another.
- **Only the override removes a shop sign from a container that cannot be vaulted**
  (`CaptureRules.mayRemoveShopSign`).
- **A placement is `VaultPlacementService` over a `PlacementStore` and a `PlacementWorld`**, so
  its order is tested with fakes. Who may place where is `PlacementRules.decide`.
- **A hanging entity follows the ordering rule too:** stored, then checked to be still there and
  unchanged, then removed. If the check fails the store is undone with `undoAppend`.
- **A chunk is held with `ChunkHolds` from store to remove** when nothing else keeps it loaded.
  It counts holders: a plugin chunk ticket is one per chunk.
- **What a capture does with a block is `DoubleChestRule.decide`**: unprotect it, refuse because
  the other half must go first, or capture. It names no Bukkit type and has its own test.
- **Nothing removes a container except `CapturePipeline`**, through `BukkitContainerAccess.remove`.
  The order is: read, store, check it is unchanged, remove.
- **Placement claims before it places and deletes after.** A vault is never deleted before its
  block exists.
- **Editing a vault claims it.** Every claim is released: on close, on a failed open, at
  shutdown, and for all vaults at startup.
- **A module that owns a table** keeps its own migrator and version table, registers its mapper
  with `VaultDatabase.registerMapper`, and reads it through `SqlSessionWrapper.mapper`. The
  auto-vault module's `PendingSweepStore` is the example.
- **Where a player may place is `Integrations.mayPlace`.** A module adds a `PlacementGrant`; a
  grant that throws counts as no.
