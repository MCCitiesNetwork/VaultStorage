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
| `:modules:chestshop-adapter` | `chestshop-adapter-<version>.jar` | ChestShop sign handling |
| `:modules:realty-autovault` | `realty-autovault-<version>.jar` | Auto-vault on a Realty occupant change |
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
| (root) | `VaultStoragePlugin`, `Integrations` |
| `capture` | capture service, capture policy, hanging support |
| `placement` | placement service |
| `vault` | vault types, the vault inventory, item serialization, events |
| `scan` | region scan service and scanner |
| `region` | WorldGuard service and region types |
| `bolt` | Bolt service |
| `chestshop` | the `ChestShopService` interface and its no-op default |
| `names` | `NameSource`, the Paper source, `Names`, `UniqueIdentifierResolver` |
| `menu` | dialog menus and `AutoDialog` |
| `command` | the `/vault` router, subcommands, `LegacyVaultCommand` |
| `permission` | `VaultPermission` |
| `session` | per-player session state |
| `config` | `AutoYML`, config paths, `MiniMessageUtil` |
| `database` | MyBatis mappers, `VaultDatabase`, `VaultRepository`, the schema migrator |

`menu`, `command` and `config` are layers, not features. Each is rewritten whole by a later
step of the programme below.

## Rules

- **The host knows Bolt, WorldGuard and WorldEdit. Every other plugin is reached through a
  module.** The host owns the interface; the module supplies the implementation by
  filling a slot in `Integrations`.
- **Host code reads `Integrations` at use time, never once at construction.** Modules start
  after the host's services exist and can stop while it runs.
- **A module's entry class names no type from its optional plugin.** Those types live in one
  separate class per module (`NamebookNameSource`, `FloodgateNames`, `ChestShopServiceImp`,
  `RealtyOccupantChangeListener` behind `RealtyBinding`). Each module's `*ModuleTest` enforces
  it by scanning the entry class file. If the entry class named such a type, loading the module
  on a server without that plugin would throw out of the host's `onEnable`.
- **An optional plugin a module needs is declared in the host's `paper-plugin.yml`** with
  `required: false` and `join-classpath: true`. Module class loaders are parented to the host's.
- **Relocations live only in `vaultstorage-shared-relocation`.** Modules apply it too, so their
  references to plugin-infrastructure land on the host's relocated copy.
- **New and rewritten classes meet the playbook's `code-style` in full.** Classes that have only
  been moved keep their old style (`getInstance()`, `get` accessors, missing nullability
  annotations) until their step.
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
- **Never commit `*.sql` from the repository root.** A production snapshot is kept there for
  testing and is ignored.

## Standardisation programme

Specs and plans are in `docs/superpowers/`. One spec and one plan per step.

| Step | Status | Delivers |
| --- | --- | --- |
| 1. Scaffold, package move, modules | built; live-server checks outstanding | this layout |
| 2. Persistence | done | MyBatis on MariaDB, the playbook schema, transactions |
| 3. Capture, placement, auto-vault | done, except shop owners from Treasury | save before destroy, futures, two more modules |
| 4. Config and messages | outline | Configurate records, `messages.yml`, reload |
| 5. Commands and permissions | outline | Brigadier, `vaultstorage.command.*` |
| 6. Menus | outline | paged vault list with real labels, confirmations |
| 7. Sweep | outline | code style everywhere, dead code removed |

Step 3 so far, on `refactor/capture-placement` (plan: `docs/superpowers/plans/2026-11-12-capture-placement-autovault.md`):

- Done: exact region lookups; `CapturePipeline` (every capture saves before it removes); sessions
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
- Not done: shop owners from Treasury's registry (see the step 3 spec's known limitations).
  Everything else in the step 3 plan is done.

## Live-server tests

`./gradlew :plugin:plugwrightTest` starts MariaDB from `compose.dev.yml` (port 3309), provisions a
Paper 26.3 server with Bolt, WorldGuard, WorldEdit, LuckPerms, GriefPrevention, ViaVersion and
ViaBackwards,
installs the host and module jars, and runs the specs in `plugin/src/test/e2e/tests/`. It is never
part of `build`. It needs Docker and Node.

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

Covered live: `/vault` opening and being refused; capturing a locked chest, finding it in the
menus and placing it back with items in their slots; a capture during a database outage leaving
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
owner; a locked item frame being vaulted with the item it shows.

Not covered live: sweeps (they need Realty to trigger one) and ChestShop. Shared steps are in `tests/vaults.ts`.

## Rules for the capture and placement code

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
