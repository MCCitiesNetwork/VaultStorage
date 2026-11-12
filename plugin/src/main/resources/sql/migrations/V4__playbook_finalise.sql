-- Removes what V3 copied from and puts the constraints back. Every clause tolerates having run
-- before. A vault with no owner fails the first ALTER on purpose: it needs one given by hand.

DELETE i FROM VaultItem i
    LEFT JOIN Vault v ON v.uuid = i.vaultUuid
    WHERE v.uuid IS NULL;

ALTER TABLE Vault
    MODIFY ownerUuid UUID NOT NULL,
    MODIFY createdTime DATETIME(3) NOT NULL,
    MODIFY updatedTime DATETIME(3) NOT NULL,
    DROP COLUMN IF EXISTS createdAtEpochMillis,
    DROP COLUMN IF EXISTS updatedAtEpochMillis,
    ADD INDEX IF NOT EXISTS idx_vault_owner (ownerUuid, createdTime),
    ADD INDEX IF NOT EXISTS idx_vault_world (worldUuid, createdTime);

ALTER TABLE Vault
    ADD CONSTRAINT fk_vault_world FOREIGN KEY IF NOT EXISTS (worldUuid) REFERENCES World (uuid);

DROP TABLE IF EXISTS vault_owners;

-- One statement, so the table is never without a primary key.
ALTER TABLE VaultItem
    DROP PRIMARY KEY,
    DROP COLUMN IF EXISTS uuid,
    DROP INDEX IF EXISTS uq_vi_slot,
    ADD PRIMARY KEY (vaultUuid, slot);

ALTER TABLE VaultItem
    ADD CONSTRAINT fk_item_vault FOREIGN KEY IF NOT EXISTS (vaultUuid) REFERENCES Vault (uuid) ON DELETE CASCADE;
