package com.minecraftcitiesnetwork.vaultstorage.session;

import java.util.function.Consumer;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** One listener that is registered only while a player is in a capture or placement session. */
public final class DynamicListener {

    private final @NotNull Consumer<Listener> register;
    private @Nullable Listener listener;
    private boolean registered;

    /** @param register registers a listener with the server, for this plugin */
    public DynamicListener(@NotNull Consumer<Listener> register) {
        this.register = register;
    }

    /** Replaces the listener. The old one stops receiving events. */
    public void setListener(@NotNull Listener listener) {
        stop();
        this.listener = listener;
    }

    public void start() {
        if (this.listener != null && !this.registered) {
            this.register.accept(this.listener);
            this.registered = true;
        }
    }

    public void stop() {
        if (this.listener != null && this.registered) {
            HandlerList.unregisterAll(this.listener);
        }
        this.registered = false;
    }

    /** Stops and forgets the listener. */
    public void close() {
        stop();
        this.listener = null;
    }
}
