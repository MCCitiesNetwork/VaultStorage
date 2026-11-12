# VaultStorage

A Paper plugin that stores Bolt-locked containers as "vaults" in a database when a region
changes hands, and lets the owner place them back.

## Build

```bash
./gradlew build                 # everything: compile, Checkstyle, tests, shaded jars
./gradlew test                  # every project's tests
./gradlew :plugin:test          # the host only; the database tests need a running Docker daemon
./gradlew :plugin:runServer     # a Paper 26.3 test server with the module jars installed
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
| `database.legacy` | `VaultService`: an adapter over the repository for callers not yet rewritten |

`menu`, `command` and `config` are layers, not features. Each is rewritten whole by a later
step of the programme below.

## Rules

- **The host knows Bolt, WorldGuard, WorldEdit and Essentials. Every other plugin is reached
  through a module.** The host owns the interface; the module supplies the implementation by
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
| 3. Capture, placement, auto-vault | specified | save before destroy, futures, three more modules |
| 4. Config and messages | outline | Configurate records, `messages.yml`, reload |
| 5. Commands and permissions | outline | Brigadier, `vaultstorage.command.*` |
| 6. Menus | outline | paged vault list with real labels, confirmations |
| 7. Sweep | outline | code style everywhere, dead code removed |

`database.legacy.VaultService` keeps the old two-call shape (create a vault, then add its items),
so those two writes are still separate transactions. Until step 3 ships, capture still removes a block before its items are saved, so a failed
database write loses them. Do not treat a 2.0.0 snapshot as safer than 1.2.0.
