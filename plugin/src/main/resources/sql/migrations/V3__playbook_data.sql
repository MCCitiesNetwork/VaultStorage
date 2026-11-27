-- Copies each vault's owner and timestamps into the columns V2 added. Running it twice writes
-- the same values.

-- FROM_UNIXTIME answers in the session's time zone; the plugin stores UTC.
SET time_zone = '+00:00';

UPDATE Vault v
    JOIN vault_owners o ON o.vaultUuid = v.uuid
    SET v.ownerUuid = o.ownerUuid;

UPDATE Vault
    SET createdTime = COALESCE(
            FROM_UNIXTIME(createdAtEpochMillis / 1000),
            FROM_UNIXTIME(updatedAtEpochMillis / 1000),
            NOW(3)),
        updatedTime = COALESCE(
            FROM_UNIXTIME(updatedAtEpochMillis / 1000),
            FROM_UNIXTIME(createdAtEpochMillis / 1000),
            NOW(3));
