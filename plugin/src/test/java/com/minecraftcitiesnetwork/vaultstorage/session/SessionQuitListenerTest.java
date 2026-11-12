package com.minecraftcitiesnetwork.vaultstorage.session;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SessionQuitListenerTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void aPlayerWhoLeavesInCaptureModeComesBackWithNoSession() {
        VaultSessionManager sessions = new VaultSessionManager(listener -> { });
        sessions.getOrCreate(PLAYER).switchTo(VaultSessionManager.Mode.CAPTURE);

        new SessionQuitListener(sessions).playerLeft(PLAYER);

        Assertions.assertNull(sessions.get(PLAYER));
        Assertions.assertEquals(VaultSessionManager.Mode.NONE, sessions.getOrCreate(PLAYER).getMode());
    }

    @Test
    void aPlayerWithNoSessionCanLeave() {
        VaultSessionManager sessions = new VaultSessionManager(listener -> { });
        new SessionQuitListener(sessions).playerLeft(PLAYER);
        Assertions.assertNull(sessions.get(PLAYER));
    }
}
