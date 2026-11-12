package com.minecraftcitiesnetwork.vaultstorage.session;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * Manages a Bukkit {@link Listener} dynamically,
 * allowing it to be registered and unregistered on demand.
 * This is useful for listeners that are only needed for a short period,
 * such as during a dialog interaction.
 */
public class DynamicListener {

    private final VaultStoragePlugin plugin = VaultStoragePlugin.getInstance();
    private Listener listener;

    /**
     * Unregisters the current listener from Bukkit's event system.
     * Sets activeListener to false.
     */
    private void unRegisterListener() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
        }
    }

    /**
     * Registers the current listener with Bukkit's event system.
     * Sets activeListener to true.
     */
    private void registerListener() {
        if (listener != null) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
    }

    /**
     * Checks if the current listener is already registered with Bukkit.
     * @return True if the listener is registered, false otherwise.
     */
    private boolean checkIfRegistered() {
        for (var registeredListener : HandlerList.getRegisteredListeners(plugin)) {
            if (registeredListener.getListener() == listener) {
                return true;
            }
        }
        return false;
    }


    /**
     * Stops (unregisters) the current listener.
     */
    public void stop() {
        unRegisterListener();
    }

    /**
     * Starts (registers) the current listener if it's not already registered.
     */
    public void start() {
        if (checkIfRegistered()) return;
        registerListener();
    }

    /**
     * Sets a new Listener. If a previous listener was set, it will be unregistered.
     * @param listener The new Listener to manage.
     */
    public void setListener(Listener listener) {
        if (this.listener != null) {
            unRegisterListener();
        }
        this.listener = listener;
    }





    /**
     * Fully closes and cleans the listener reference.
     */
    public void close() {
        unRegisterListener();
        listener = null;
    }
}
