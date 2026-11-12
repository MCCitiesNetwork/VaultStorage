package com.minecraftcitiesnetwork.vaultstorage.session;

import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Ends a player's capture or placement session when they leave. Without it the session's
 * listener stayed registered, so a player who rejoined was still in capture mode with no action
 * bar to say so, and their next right-click on a container could vault it.
 */
public final class SessionQuitListener implements Listener {

    private final @NotNull VaultSessionManager sessions;

    public SessionQuitListener(@NotNull VaultSessionManager sessions) {
        this.sessions = sessions;
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        playerLeft(event.getPlayer().getUniqueId());
    }

    /** Unregisters the session's listener, cancels its action bar and forgets the session. */
    public void playerLeft(@NotNull UUID player) {
        this.sessions.end(player);
    }
}
