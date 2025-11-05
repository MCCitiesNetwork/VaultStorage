package net.democracycraft.vault.internal.util.listener;

import net.democracycraft.vault.VaultStoragePlugin;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Manages a Bukkit {@link Listener} dynamically,
 * allowing it to be registered and unregistered on demand.
 * This is useful for listeners that are only needed for a short period,
 * such as during a dialog interaction.
 */
public class DynamicListener {

    private final VaultStoragePlugin plugin = VaultStoragePlugin.getInstance();
    private boolean activeListener = false;
    private Listener listener;

    /**
     * Unregisters the current listener from Bukkit's event system.
     * Sets activeListener to false.
     */
    private void unRegisterListener() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            activeListener = false;
        }
    }

    /**
     * Registers the current listener with Bukkit's event system.
     * Sets activeListener to true.
     */
    private void registerListener() {
        if (listener != null) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            activeListener = true;
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
     * Unregisters the current listener (if any) and sets it to null.
     */
    public void deleteListener() {
        if (listener != null) {
            unRegisterListener();
        }
        listener = null;
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
     * Checks if a listener is currently set.
     * @return True if a listener is set, false otherwise.
     */
    public boolean isPresent() {
        return listener != null;
    }

    /**
     * Gets the currently managed Listener.
     * @return The current Listener, or null if none is set.
     */
    public Listener getListener() {
        return listener;
    }

    /**
     * Checks if the managed listener is currently active (registered).
     * @return True if the listener is active, false otherwise.
     */
    public boolean isActive() {
        return activeListener;
    }

    /**
     * Stops (unregisters) the current listener after a delay.
     * @param time Delay in ticks.
     */
    public void stopListenerAfter(long time) {
        new BukkitRunnable() {
            @Override
            public void run() {
                stop();
            }
        }.runTaskLater(plugin, time);
    }

    /**
     * Fully closes and cleans the listener reference.
     */
    public void close() {
        unRegisterListener();
        listener = null;
    }
}
