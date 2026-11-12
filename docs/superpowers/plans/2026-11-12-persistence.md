# Persistence Implementation Plan

**Goal:** Replace `AutoTable` with MyBatis on MariaDB and convert the live database to the playbook schema, keeping every row.
**Spec:** `docs/superpowers/specs/2026-11-12-persistence-design.md`

Conventions: `D` is `plugin/src/main/java/com/minecraftcitiesnetwork/vaultstorage/database`,
`DT` the matching test directory, `SQL` is `plugin/src/main/resources/sql/migrations`. Branch
`refactor/persistence` from `refactor/playbook-scaffold`. Every task ends with `./gradlew build`
green and a commit. Database tests need a running Docker daemon and fail without one.

## Task 1: Migrations

**Files:** modify `gradle/libs.versions.toml`, `plugin/build.gradle.kts`, `buildSrc/.../vaultstorage-shared-relocation.gradle.kts`; create `SQL/V1__legacy_baseline.sql`, `SQL/V2__playbook_structure.sql`, `SQL/V3__playbook_data.sql`, `SQL/V4__playbook_finalise.sql`, `D/VaultStorageSchemaMigrator.java`, `DT/DatabaseTestBase.java`, `DT/MigrationTest.java`, `plugin/src/test/resources/legacy-rows.sql`

**Interfaces:**
```java
public final class VaultStorageSchemaMigrator extends SchemaMigrator {
    public static final List<MigrationStep> MIGRATIONS;   // versions 1 to 4
    public static final String RESOURCE_DIR = "sql/migrations";
}
abstract class DatabaseTestBase {            // one static mariadb:10.11 container
    static DataSource dataSource();
    static void dropEverything();            // drops every table, for a clean start
    static void migrate();                   // runs MIGRATIONS
}
```

- [ ] Catalog: `mybatis = "3.5.19"`, `mariadb = "3.5.6"`, `testcontainers-mariadb = "2.0.1"`, `testcontainers-junit = "2.0.2"`; libraries `mybatis`, `mariadb-java-client`, `testcontainers-mariadb`, `testcontainers-junit-jupiter`. Host: `implementation` for the first two, `testImplementation` for the last two. Relocation list gains `org.apache.ibatis` and `org.mariadb.jdbc`. The host's `test` task sets `DOCKER_HOST`, `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` and `DOCKER_API_VERSION=1.44` the way homeland-security's build does, because a Gradle test JVM inherits no shell environment and Docker Engine 29 rejects older API versions.
- [ ] Write the failing tests in `MigrationTest`:
  - `anEmptyDatabaseMigratesToTheLatestVersion`: after `migrate()`, `SELECT MAX(version)` from `vaultstorage_schema_version` is 4 and tables `Vault`, `VaultItem`, `World` exist while `vaults`, `vault_items`, `vault_owners`, `worlds` do not.
  - `migratingTwiceChangesNothing`: a second `migrate()` succeeds and the version is still 4.
  - `aLegacyDatabaseKeepsItsRows`: apply `V1__legacy_baseline.sql` by hand, load `legacy-rows.sql` (two worlds, three vaults with owners, five items including a 60,000-byte blob, timestamps `1731134320396` and `1762927416979`), then `migrate()`. Assert three vaults; for one vault its owner, world, position, material, `createdTime` equal to `Instant.ofEpochMilli(1731134320396)`; the blob's length and first and last bytes; and that `VaultItem` has primary key `(vaultUuid, slot)`.
  - `eachConversionStepCanRunAgain`: after `migrate()` on the legacy fixture, execute every statement of V2, then V3 is skipped (its source columns are gone, which is why the version table guards it), then V4, and assert no error and unchanged row counts.
  - `theItemColumnHoldsMoreThanSixtyFourKilobytes`: insert a 100,000-byte item after migrating and read it back.
- [ ] Run `./gradlew :plugin:test --tests "*MigrationTest"` and expect FAIL: no migrator class.
- [ ] Implement the scripts.
  - V1: the four `CREATE TABLE` statements from the production dump, each with `IF NOT EXISTS`, in the order `worlds`, `vaults`, `vault_owners`, `vault_items`.
  - V2, in this order so that a re-run meets the names it expects: `RENAME TABLE IF EXISTS` for `vaults` to `Vault`, `vault_items` to `VaultItem`, `worlds` to `World`; `DROP FOREIGN KEY IF EXISTS` for `fk_vi_vault` on `VaultItem`, `fk_vo_vault` on `vault_owners`, `fk_vault_world` on `Vault`; on `World`, fill a null `name` with the UUID text, drop `uq_world_name` and `name_idx`, make `uuid` a `UUID` and `name` `NOT NULL`; on `Vault`, set a null `material` to `CHEST`, drop the ten single-column indexes and `idx_vault_loc`, drop `type`, convert `uuid` and `worldUuid` to `UUID NOT NULL`, `blockData` to `TEXT`, `material` to `VARCHAR(64) NOT NULL`, `status` to `VARCHAR(16)`, and add nullable `ownerUuid UUID`, `createdTime DATETIME(3)`, `updatedTime DATETIME(3)`; on `vault_owners`, drop `ownerUuid_idx` and convert both columns to `UUID`; on `VaultItem`, delete rows with a null `vaultUuid` or `item`, drop `item_idx`, `amount_idx`, `slot_idx`, `vaultUuid_idx`, convert `vaultUuid` to `UUID NOT NULL` and `item` to `MEDIUMBLOB NOT NULL`.
  - V3: `SET time_zone = '+00:00'`; copy `ownerUuid` from `vault_owners` by join; set `createdTime` and `updatedTime` from `FROM_UNIXTIME(<millis> / 1000)`, using the other timestamp or `NOW(3)` when one is null.
  - V4: delete `VaultItem` rows whose vault is gone; on `Vault`, make the three new columns `NOT NULL`, drop the two epoch columns, add `idx_vault_owner (ownerUuid, createdTime)`, `idx_vault_world (worldUuid, createdTime)` and `fk_vault_world`; `DROP TABLE IF EXISTS vault_owners`; on `VaultItem`, in one statement drop the primary key, drop `uuid` and `uq_vi_slot`, add primary key `(vaultUuid, slot)`, then add `fk_item_vault ... ON DELETE CASCADE`.
- [ ] Implement `VaultStorageSchemaMigrator` with version table `vaultstorage_schema_version`.
- [ ] Run the test class and expect PASS, 5 tests. Run `./gradlew build`. Commit: `feat: migrate the database to the playbook schema`.

## Task 2: Mappers and the database class

**Files:** create `D/UuidHandler.java`, `D/VaultEntity.java`, `D/VaultItemEntity.java`, `D/VaultSummary.java`, `D/VaultMapper.java`, `D/MariaVaultMapper.java`, `D/VaultItemMapper.java`, `D/MariaVaultItemMapper.java`, `D/WorldMapper.java`, `D/MariaWorldMapper.java`, `D/SqlSessionWrapper.java`, `D/MariaSqlSession.java`, `D/VaultDatabase.java`, `D/DatabaseSettings.java`

**Interfaces:**
```java
public record DatabaseSettings(String host, int port, String database, String user, String password, boolean useSsl) { String jdbcUrl(); }
public final class VaultDatabase implements AutoCloseable {
    public VaultDatabase(@NotNull DatabaseSettings settings);      // builds pool and factory
    public VaultDatabase(@NotNull DataSource dataSource);          // for tests
    public @NotNull DataSource dataSource();
    public @NotNull SqlSessionWrapper openSession();               // transactional
    public @NotNull SqlSessionWrapper openSession(boolean autoCommit);
}
```
Mapper methods are the statements `VaultRepository` needs in Task 3 and are tested through it.

- [ ] No test of its own: Task 3's repository tests are the first thing that can fail for a missing mapper. Write the classes, run `./gradlew build`, commit `feat: MyBatis mappers for the vault schema`.

## Task 3: `VaultRepository`

**Files:** create `D/VaultRepository.java`, `D/NewVault.java`, `D/VaultItem.java`, `D/StoredVault.java`, `D/VaultFilter.java`, `D/VaultPage.java`, the six sealed result interfaces, `DT/VaultRepositoryTest.java`

**Interfaces:** the method table in the spec's "`VaultRepository`" section, unchanged.

- [ ] Write the failing tests, one per bullet of the spec's "Testing" section from "`create` is atomic" to "`delete`, `setOwner` and `replaceItems` return `NoSuchVault`". The atomicity test passes two items with the same slot, so the batch violates the primary key, and asserts `find` returns `NoSuchVault`.
- [ ] Run `./gradlew :plugin:test --tests "*VaultRepositoryTest"` and expect FAIL: no repository.
- [ ] Implement, one session per method, `commit()` at the end of every transactional one.
- [ ] Expect PASS. Run `./gradlew build`. Commit: `feat: a transactional vault repository`.

## Task 4: Switch the plugin over

**Files:** modify `P/VaultStoragePlugin.java`, `D/VaultServiceImpl.java`, every caller that reads a field off an entity; delete `MySQLManager`, `DatabaseSchema`, `AutoTable`, `AbstractTable`, `Column`, `VaultDAO`, `VaultDAOImpl`, `VaultOwnerEntity`, `VaultMemberEntity`, `VaultRegionEntity`, `VaultRegionLinkEntity`, `WorldEntity`, `VaultMemoryStore`; modify `README.md`, `CLAUDE.md`

- [ ] `onEnable`: build `DatabaseSettings` from the `mysql.*` keys, `VaultDatabase`, run the migrator; on any exception log at SEVERE, `disablePlugin(this)`, return. Create the `vaultstorage-db` pool of 4 threads; `onDisable` stops modules, shuts the pool down with a 10-second wait, closes the database.
- [ ] `VaultServiceImpl` delegates each existing method to `VaultRepository`; callers change `entity.field` to `entity.field()`.
- [ ] Delete the old layer. `./gradlew build`.
- [ ] Manual: load the production snapshot into a `mariadb:10.11` container, boot with `runServer`, confirm versions 1 to 4 apply, the counts are 82,705 vaults and 1,068,403 items, and re-time the list query at offset 60,000.
- [ ] Update `README.md` (MariaDB 10.11 required, backup before upgrading, about 70 seconds of migration) and `CLAUDE.md` (step 2 done, database test instructions). Commit: `refactor: run on the MyBatis repository and drop AutoTable`.
