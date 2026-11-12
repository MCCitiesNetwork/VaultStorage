package com.minecraftcitiesnetwork.vaultstorage.vault;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public abstract class VaultStorageEvent extends Event {
    public static HandlerList handlerList = new HandlerList();
    @Override
    public @NotNull HandlerList getHandlers() {
        return handlerList;
    }
}
