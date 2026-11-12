# Persistence — design

Date: 2026-11-12
Status: awaiting review
Step 2 of 7. Depends on step 1 (`2026-11-12-playbook-scaffold-and-package-move-design.md`).

## Goal

Replace the reflection-based `AutoTable` layer with MyBatis and convert the live database to the
playbook's schema conventions, keeping every row. After this step every write is atomic, every
list is one query, large items fit, and the plugin refuses to run without a working database.

## Audit findings this step closes

- One JDBC connection behind a global lock; a stale connection fails the first query after idle.
- No transactions: a vault, its owner and its items are separate writes.
- `vault_items.item` is `BLOB`, capped at 64 KB.
- Schema and connection failures are logged or swallowed, and the plugin keeps running.
- One query per vault in `listByOwner`, owner lookups and the hanging merge.
- A world loaded after startup has no `worlds` row, so vault creation there fails.

## What production looks like

Measured on a snapshot of the production database (MariaDB 10.11.19) taken 2026-11-12:

| Fact | Value |
| --- | --- |
| Vaults | 82,705, of which 71,375 are in one world |
| Items | 1,068,403 |
| Owners | 4,865; the largest holds 1,323 vaults |
| Largest stored item | 43,016 bytes, against a 65,535-byte limit |
| Size | 319 MB of data, 776 MB of indexes |
| Vaults with no owner row | 0 |
| Vaults with no items | 1 |
| `status` and `type` | `NULL` in every row |
| Highest slot | 26 |

Two things follow. The admin vault list today asks for 71,375 buttons and runs about 143,000
queries on the main thread. And most of the database is one index, `item_idx`, on the first
3,072 bytes of every item, which nothing uses.

## Engine

The playbook's question: does the data only need to survive a restart, or is it read from
outside the plugin? Only this plugin reads it, which would normally mean SQLite. It stays on the
server database anyway, because the live data is already there and moving engines would be a
data migration with no benefit.

Dialect: **MariaDB, 10.11 or later**. Mapper sub-interfaces are named `Maria...`. MySQL is not
supported from this step on: the schema uses MariaDB's native `UUID` type, and the migrations
use its `IF EXISTS` and `IF NOT EXISTS` clauses on `ALTER TABLE`.

Driver: MariaDB Connector/J, shaded and relocated, with a `jdbc:mariadb:` URL built from the
existing `mysql.*` config keys. Today the plugin reaches MariaDB through the MySQL driver that
Paper ships, which works but is not a combination either vendor supports. The config keys keep
their `mysql.` prefix until step 4, so operators change nothing. The connection's time zone is
pinned to UTC.

## Schema

The live tables are converted to the playbook's conventions: PascalCase tables, native `UUID`
columns, `DATETIME` timestamps mapped to `Instant`. The conversion was run against the
production snapshot: it took 70 seconds, 67 of them rebuilding the item table, every row
survived, and the database shrank from 1.1 GB to 362 MB.

```sql
World     (uuid UUID PRIMARY KEY, name VARCHAR(255) NOT NULL)

Vault     (uuid UUID PRIMARY KEY,
           ownerUuid UUID NOT NULL,
           worldUuid UUID NOT NULL REFERENCES World(uuid),
           x INT NOT NULL, y INT NOT NULL, z INT NOT NULL,
           material VARCHAR(64) NOT NULL,
           blockData TEXT NULL,
           status VARCHAR(16) NULL,
           createdTime DATETIME(3) NOT NULL,
           updatedTime DATETIME(3) NOT NULL,
           INDEX idx_vault_owner (ownerUuid, createdTime),
           INDEX idx_vault_world (worldUuid, createdTime))

VaultItem (vaultUuid UUID NOT NULL REFERENCES Vault(uuid) ON DELETE CASCADE,
           slot INT NOT NULL,
           amount INT NOT NULL,
           item MEDIUMBLOB NOT NULL,
           PRIMARY KEY (vaultUuid, slot))
```

What changes from the live shape, and why:

| Change | Reason |
| --- | --- |
| `vaults`, `vault_items`, `worlds` renamed | Playbook naming |
| `vault_owners` folded into `Vault.ownerUuid` | It is one row per vault; a separate table buys nothing and costs a join on every list |
| `VARCHAR(36)` keys to `UUID` | Playbook; also 16 bytes per key instead of 36 |
| `createdAtEpochMillis`, `updatedAtEpochMillis` to `createdTime`, `updatedTime` | Playbook |
| `VaultItem` loses its synthetic `uuid`; the key is `(vaultUuid, slot)` | Nothing ever looked a row up by that id |
| `item` to `MEDIUMBLOB` | 16 MB limit instead of 64 KB; the largest item is already two thirds of the way there |
| `blockData` to `TEXT` | It is `VARCHAR(255)` today |
| Unused `type` column dropped | `NULL` in every row |
| Every `AutoTable` single-column index dropped, including `item_idx` | Unused; they are two thirds of the database |
| `uq_world_name` dropped | A regenerated world keeps its name and gets a new UUID; the unique index then blocks its row |

`status` is kept and gains a meaning: `NULL` for a stored vault, `CLAIMED` while one actor is
placing or editing it. See `claim` below.

### Migrations

`VaultStorageSchemaMigrator extends SchemaMigrator` (plugin-infrastructure), version table
`vaultstorage_schema_version`, scripts under `plugin/src/main/resources/sql/migrations/`.

| Script | Does | Safe to re-run because |
| --- | --- | --- |
| `V1__legacy_baseline.sql` | `CREATE TABLE IF NOT EXISTS` for the four legacy tables, exactly as production has them | No-op on a live database; on an empty one it creates the legacy shape |
| `V2__playbook_structure.sql` | Drops the old foreign keys and unused indexes, renames the tables, converts keys to `UUID`, widens `item` and `blockData`, adds `ownerUuid`, `createdTime`, `updatedTime` as nullable | Every clause uses `IF EXISTS` or `IF NOT EXISTS` |
| `V3__playbook_data.sql` | Copies the owner from `vault_owners` and the two timestamps into the new columns | Plain `UPDATE`; running it twice writes the same values |
| `V4__playbook_finalise.sql` | Makes the new columns `NOT NULL`, drops the old columns and `vault_owners`, sets the new primary key on `VaultItem`, adds the indexes and foreign keys | Every clause uses `IF EXISTS` or `IF NOT EXISTS` |

An empty database and the live one take the same path, V1 to V4, so the path production takes
is the one every test run exercises.

MariaDB commits each `ALTER TABLE` on its own, so a crash can leave a step half applied. The
split into three steps keeps each one re-runnable: structure, then data while both old and new
columns exist, then removal of the old.

Timestamps are converted with `TIMESTAMPADD` from the epoch, not `FROM_UNIXTIME`, so the result
does not depend on the session time zone.

After migrating, startup logs the number of vaults with no items at WARN when it is not zero.
Production has one. It is reported, not repaired.

## Architecture

All new code is in the host, under `com.minecraftcitiesnetwork.vaultstorage.database`.

### Records

```java
record VaultEntity(UUID uuid, UUID ownerUuid, UUID worldUuid, int x, int y, int z,
                   String material, @Nullable String blockData, @Nullable String status,
                   Instant createdTime, Instant updatedTime)
record VaultItemEntity(UUID vaultUuid, int slot, int amount, byte[] item)
record WorldEntity(UUID uuid, String name)

record VaultSummary(UUID uuid, UUID ownerUuid, UUID worldUuid, int x, int y, int z,
                    String material, Instant createdTime, int itemCount)
record StoredVault(VaultEntity vault, List<VaultItemEntity> items)
record NewVault(UUID worldUuid, String worldName, int x, int y, int z, UUID ownerUuid,
                String material, @Nullable String blockData)
record VaultItem(int slot, int amount, byte[] item)
record VaultFilter(@Nullable UUID ownerUuid, @Nullable UUID worldUuid)
record VaultPage(List<VaultSummary> vaults, int total)
```

### Mappers and sessions

`VaultMapper`, `VaultItemMapper`, `WorldMapper`, each with a `Maria...` sub-interface holding the
SQL as text blocks. `VaultDatabase` builds MyBatis's `PooledDataSource` (ping query `SELECT 1`,
so a connection the server dropped is replaced instead of used) and the `SqlSessionFactory`,
and registers each mapper explicitly. `openSession()` returns a `SqlSessionWrapper`
(implemented by the record `MariaSqlSession`) with one accessor per mapper; it is transactional,
and `openSession(true)` is the autocommit form for single statements and reads.

### `VaultRepository`

One blocking method per unit of work. Each opens one session; anything with more than one
statement is one transaction. It never touches Bukkit, so it is tested without a server.

Outcomes a caller must handle are sealed result types, not booleans, `Optional`s or exceptions:

```java
sealed interface FindResult     { record Found(StoredVault vault); record NoSuchVault(); }
sealed interface DeleteResult   { record Deleted();                record NoSuchVault(); }
sealed interface OwnerResult    { record Changed(UUID previous);   record NoSuchVault(); }
sealed interface ReplaceResult  { record Replaced();               record NoSuchVault(); }
sealed interface ClaimResult    { record Claimed(StoredVault vault); record AlreadyClaimed();
                                  record NoSuchVault(); }
sealed interface AppendResult   { record Appended(UUID vault);     record Created(UUID vault); }
```

| Method | Statements, in one transaction |
| --- | --- |
| `UUID create(NewVault, List<VaultItem>)` | upsert the world row; insert the vault; batch-insert the items |
| `FindResult find(UUID)` | the vault; its items ordered by slot |
| `VaultPage page(VaultFilter, int offset, int limit)` | one count; one select with the item count, ordered by `createdTime, uuid` |
| `int count(VaultFilter)` | one count |
| `DeleteResult delete(UUID)` | one delete, decided by its row count; items go by cascade |
| `OwnerResult setOwner(UUID vault, UUID owner)` | select the current owner `FOR UPDATE`; update |
| `ReplaceResult replaceItems(UUID vault, List<VaultItem>)` | delete the vault's items; batch-insert the new ones; set `updatedTime` |
| `AppendResult appendItems(UUID owner, List<VaultItem>, int maxSlots, NewVault ifNoneFits)` | select the owner's oldest vault whose highest slot leaves room, `FOR UPDATE`; insert there, or create a new vault |
| `ClaimResult claim(UUID)` | `UPDATE Vault SET status='CLAIMED' WHERE uuid=? AND status IS NULL`; one row changed means claimed, then load it; none means look up which of the other two cases it is |
| `void release(UUID)` | set `status` back to `NULL` |
| `List<UUID> releaseAllClaims()` | release every claimed vault and return their ids |

`claim` is a compare-and-set in the database. Step 3 uses it so that a vault can be placed or
edited by one actor at a time; this step only provides and tests it.

`appendItems` replaces both the per-owner JVM lock map in `VaultCaptureService` and the loop in
`HangingVaultSupport.findFirstVaultWithSpace` that reads every vault and every item of an owner.

### Wiring and failure

`VaultStoragePlugin.onEnable` builds `VaultDatabase`, runs the migrator, then builds the
repository. If the connection or a migration fails it logs at SEVERE with the exception, calls
`disablePlugin(this)` and returns. Nothing registers, so no capture can run against a database
that cannot store it.

The plugin creates one database executor: a fixed pool of 4 threads named `vaultstorage-db`.
`onDisable` stops the modules, shuts the pool down with a bounded wait, then closes the data
source. The connection pool allows the same 4 connections.

### Keeping the rest of the plugin compiling

Capture, placement, menus and commands are rewritten in step 3 and later, and they call
`VaultService` today. In this step `VaultServiceImpl` becomes a thin adapter over
`VaultRepository` with the same method names, so those callers change only where they read a
field off an entity (`vault.uuid` becomes `vault.uuid()`):

- `listByOwner` and `listInWorld` become one query each.
- `putItems` and `delete` become synchronous and atomic.
- `createVault` still sends its mail, in the same place.

Deleted: `MySQLManager`, `DatabaseSchema`, `AutoTable`, `AbstractTable`, `Column`, `VaultDAO`,
`VaultDAOImpl`, the old mutable entity classes, the unused `VaultMemberEntity`,
`VaultRegionEntity` and `VaultRegionLinkEntity`, and `VaultMemoryStore`.

## Conventions that apply

`database`, `threading` (the executor and its shutdown), `code-style`, `testing`. Shadow
relocates MyBatis and the MariaDB driver through `vaultstorage-shared-relocation`.

Set aside: the playbook's "delete directly and check the row count" is followed, but
`setOwner` reads before it writes, because the caller reports the previous owner.

## Error handling

- A broken database is an exception: it propagates out of the repository as MyBatis's
  `PersistenceException`. In this step the adapter's callers handle it exactly as badly as
  today; step 3 gives every chain an `exceptionally`.
- Everything else is a sealed result case.
- A transaction that throws is closed without `commit()`, which rolls it back.

## Testing

Integration tests against a real MariaDB in Testcontainers, image pinned to `mariadb:10.11` to
match production, one shared container, tables truncated before each test. They need Docker and
fail without it.

- An empty database migrates to version 4, and migrating again changes nothing.
- A legacy database migrates with its rows intact: same vault count, each vault's owner, world,
  position and timestamps to the millisecond, each item's bytes. The fixture is the four
  `CREATE TABLE` statements from the production dump, copied verbatim into
  `plugin/src/test/resources`, plus a few invented rows. The table definitions hold no player
  data; the dump's rows are never committed.
- Re-running V2, V3 and V4 one at a time after each has applied changes nothing.
- `create` is atomic: when an item insert fails, no vault row remains.
- A 100 KB item round-trips byte for byte.
- `create` succeeds in a world with no `World` row, and in one whose name another row holds.
- `page` returns the requested slice in a stable order with the right total and item counts, for
  each filter combination.
- `claim` returns `Claimed` once, `AlreadyClaimed` until `release`, and `NoSuchVault` for an
  unknown id.
- `appendItems` picks the oldest vault with room, creates one when none fits, and two concurrent
  appends for one owner never write the same slot.
- `delete`, `setOwner` and `replaceItems` return `NoSuchVault` for an unknown id.

Manual, with the production snapshot loaded into a local MariaDB 10.11 container: the plugin
enables, the log shows versions 1 to 4 applied, the row counts match the table above, and an
existing vault opens with its items.

## Known limitations

- **Capture and placement are still unsafe after this step.** Each database call is now atomic,
  but capture still removes the block before calling it. Step 3 fixes the order.
- **The vault list still loads on the main thread and without paging** until step 3, though it
  now costs two queries instead of two per vault.
- **Upgrading takes the database offline for about a minute** (40 seconds on the production snapshot through the plugin itself) while the item table is rebuilt.
  The plugin is not enabled yet at that point, so nothing is writing.
- **The upgrade cannot be undone by the plugin.** Going back to 1.2.0 means restoring a backup
  taken before the upgrade. The README and the release notes say so.
- **A database holding a vault with no owner row fails V4** and the plugin does not enable.
  Production has none. That is the safe outcome; the fix is to give the vault an owner by hand.
- **Deep pages use `OFFSET`.** Measured on the converted production snapshot: the first page of
  the largest world takes 0.04 s, the last (offset 60,000) 0.83 s, off the main thread. Keyset
  paging would remove that; it is not worth the extra code until someone pages that far.
- **The old two-call shape survives in `database.legacy.VaultService`.** Capture, placement,
  menus and commands still create a vault and then add its items, as two transactions, through
  an adapter that hands them the mutable entity classes they were written against. The adapter
  and those classes go when step 3 and later rewrite the callers. This kept every caller
  unchanged in this step, at the cost of a package that exists only to be deleted.
- **MySQL servers are no longer supported.**
