package net.democracycraft.vault.internal.notification;

import org.bukkit.plugin.Plugin;
import java.io.IOException;
import java.util.UUID;

/** Optional inbox bridge with a durable fallback when the provider is unavailable. */
public final class OfflineNotifications {
    private final NotificationOutbox outbox;
    public OfflineNotifications(Plugin plugin) {
        outbox = new NotificationOutbox(plugin.getDataFolder().toPath().resolve("pending-notifications"), notice -> {
            if (!plugin.getServer().getPluginManager().isPluginEnabled("PlayerNotifications")) return false;
            try {
                Class.forName("io.github.md5sha256.playernotifications.api.NotificationService", false,
                        plugin.getClass().getClassLoader());
            } catch (ClassNotFoundException exception) { return false; }
            return PlayerNotificationsDelivery.deliver(plugin, notice);
        }, plugin.getLogger());
        // Bukkit owns/cancels this task with the plugin; pending files survive a restart.
        plugin.getServer().getScheduler().runTaskTimer(plugin, outbox::flush, 1L, 1200L);
    }
    public boolean send(UUID recipient, UUID sender, String senderName, String message) throws IOException {
        return outbox.submit(recipient, sender, senderName, message);
    }
}
