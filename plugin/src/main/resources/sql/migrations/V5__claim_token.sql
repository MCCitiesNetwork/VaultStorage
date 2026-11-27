-- A vault is claimed while it has a token, and only a release that names the token frees it.
-- Re-runnable. status, which 1.x never wrote, goes in V2; it is dropped here too for a database
-- that an earlier build of 2.0 migrated, where it was the claim flag.

ALTER TABLE Vault
    ADD COLUMN IF NOT EXISTS claimToken UUID NULL,
    DROP COLUMN IF EXISTS status;
