package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VaultOwnersTest {

    private static final UUID ZERO = new UUID(0L, 0L);
    private static final UUID ACTOR = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID LOCK_OWNER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    @Test
    void aValidOwnerIsUsedAsItIs() {
        Assertions.assertEquals(VaultOwners.Choice.USE, VaultOwners.choose(LOCK_OWNER, ACTOR));
        Assertions.assertEquals(VaultOwners.Choice.USE, VaultOwners.choose(ACTOR, ACTOR));
    }

    @Test
    void aBrokenIdForTheCapturingPlayerThemselvesIsLookedUpByTheirName() {
        // No lock owner, so the vault is the actor's own; only their own identity is being repaired.
        Assertions.assertEquals(VaultOwners.Choice.RESOLVE_ACTOR, VaultOwners.choose(ZERO, ZERO));
    }

    @Test
    void aBrokenLockOwnerNeverBecomesTheCapturingPlayer() {
        // Someone else's container with an unusable owner id: the items must not go to whoever clicked.
        Assertions.assertEquals(VaultOwners.Choice.ABORT, VaultOwners.choose(ZERO, ACTOR));
        Assertions.assertEquals(VaultOwners.Choice.ABORT, VaultOwners.choose(null, ACTOR));
    }
}
