-- Renames the tables and converts column types. Old and new columns exist side by side
-- afterwards, so V3 can copy between them. Every clause tolerates having run before.

-- Renames come first, so that a re-run finds the names the rest of the script uses.
RENAME TABLE IF EXISTS vaults TO Vault;
RENAME TABLE IF EXISTS vault_items TO VaultItem;
RENAME TABLE IF EXISTS worlds TO World;

-- A key column cannot change type while a foreign key holds it. V4 puts the keys back.
ALTER TABLE VaultItem DROP FOREIGN KEY IF EXISTS fk_vi_vault;
ALTER TABLE VaultItem DROP FOREIGN KEY IF EXISTS fk_item_vault;
ALTER TABLE IF EXISTS vault_owners DROP FOREIGN KEY IF EXISTS fk_vo_vault;
ALTER TABLE Vault DROP FOREIGN KEY IF EXISTS fk_vault_world;

UPDATE World SET name = CAST(uuid AS CHAR) WHERE name IS NULL;
ALTER TABLE World
    DROP INDEX IF EXISTS uq_world_name,
    DROP INDEX IF EXISTS name_idx,
    MODIFY uuid UUID NOT NULL,
    MODIFY name VARCHAR(255) NOT NULL;

-- Placement already treats a missing material as a chest.
UPDATE Vault SET material = 'CHEST' WHERE material IS NULL;
ALTER TABLE Vault
    DROP INDEX IF EXISTS blockData_idx,
    DROP INDEX IF EXISTS createdAtEpochMillis_idx,
    DROP INDEX IF EXISTS material_idx,
    DROP INDEX IF EXISTS status_idx,
    DROP INDEX IF EXISTS type_idx,
    DROP INDEX IF EXISTS updatedAtEpochMillis_idx,
    DROP INDEX IF EXISTS worldUuid_idx,
    DROP INDEX IF EXISTS x_idx,
    DROP INDEX IF EXISTS y_idx,
    DROP INDEX IF EXISTS z_idx,
    DROP INDEX IF EXISTS idx_vault_loc,
    DROP COLUMN IF EXISTS type,
    MODIFY uuid UUID NOT NULL,
    MODIFY worldUuid UUID NOT NULL,
    MODIFY blockData TEXT NULL,
    MODIFY material VARCHAR(64) NOT NULL,
    DROP COLUMN IF EXISTS status,
    ADD COLUMN IF NOT EXISTS ownerUuid UUID NULL AFTER uuid,
    ADD COLUMN IF NOT EXISTS createdTime DATETIME(3) NULL,
    ADD COLUMN IF NOT EXISTS updatedTime DATETIME(3) NULL;

ALTER TABLE IF EXISTS vault_owners
    DROP INDEX IF EXISTS ownerUuid_idx,
    MODIFY vaultUuid UUID NOT NULL,
    MODIFY ownerUuid UUID NULL;

-- Rows no vault can reach, and empty slots stored as rows.
DELETE FROM VaultItem WHERE vaultUuid IS NULL OR item IS NULL;
ALTER TABLE VaultItem
    DROP INDEX IF EXISTS item_idx,
    DROP INDEX IF EXISTS amount_idx,
    DROP INDEX IF EXISTS slot_idx,
    DROP INDEX IF EXISTS vaultUuid_idx,
    MODIFY vaultUuid UUID NOT NULL,
    MODIFY item MEDIUMBLOB NOT NULL;
