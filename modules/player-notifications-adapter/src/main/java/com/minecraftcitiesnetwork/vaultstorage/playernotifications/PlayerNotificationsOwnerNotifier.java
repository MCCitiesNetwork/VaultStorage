package com.minecraftcitiesnetwork.vaultstorage.playernotifications;

import com.minecraftcitiesnetwork.vaultstorage.vault.OwnerNotifier;
import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.render.RenderableNotification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Delivers owner notifications through PlayerNotifications, which keeps them in the owner's
 * inbox and sends them over whichever media that player chose.
 *
 * <p>The only class that names PlayerNotifications' types.
 */
public final class PlayerNotificationsOwnerNotifier implements OwnerNotifier {

    private static final String DATA_TYPE = "vaultstorage";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /** What is stored: both parts as MiniMessage, so they survive as text in the database. */
    public record VaultNotification(@NotNull String title, @NotNull String message) {
    }

    private final @NotNull NotificationService notifications;
    private final @NotNull Logger logger;

    private PlayerNotificationsOwnerNotifier(@NotNull NotificationService notifications, @NotNull Logger logger) {
        this.notifications = notifications;
        this.logger = logger;
    }

    /** Null when PlayerNotifications is installed but has not registered its service. */
    public static @Nullable OwnerNotifier register(@NotNull ServicesManager services, @NotNull Logger logger) {
        NotificationService notifications = services.load(NotificationService.class);
        if (notifications == null) {
            return null;
        }
        // A renderable, not a payload with its own processor: a processor would win dispatch and
        // bypass the recipient's delivery preferences, which are the point of using this.
        notifications.registerJsonRenderable(DATA_TYPE, VaultNotification.class,
                (payload, recipient) -> new RenderableNotification(
                        MINI_MESSAGE.deserialize(payload.title()), MINI_MESSAGE.deserialize(payload.message())));
        return new PlayerNotificationsOwnerNotifier(notifications, logger);
    }

    @Override
    public void deliver(@NotNull UUID recipient, @NotNull Component title, @NotNull Component message) {
        // Its own key each time: two vaulted containers are two things the owner should read.
        String key = DATA_TYPE + ":" + recipient + ":" + UUID.randomUUID();
        try {
            this.notifications.enqueueNotification(new TypedNotification<>(key, Instant.now(), null,
                    new NotificationTarget(List.of(recipient)), DATA_TYPE,
                    new VaultNotification(MINI_MESSAGE.serialize(title), MINI_MESSAGE.serialize(message)), 0), false);
        } catch (RuntimeException ex) {
            this.logger.log(Level.WARNING, "Could not enqueue a notification for " + recipient + ".", ex);
        }
    }
}
