package net.democracycraft.vault.internal.notification;

import io.github.md5sha256.playernotifications.api.NotificationService;
import io.github.md5sha256.playernotifications.api.NotificationTarget;
import io.github.md5sha256.playernotifications.api.TypedNotification;
import io.github.md5sha256.playernotifications.api.mail.MailPayload;
import org.bukkit.plugin.Plugin;
import java.util.List;

/** Loaded only after the optional PlayerNotifications API has been located. */
final class PlayerNotificationsDelivery {
    static boolean deliver(Plugin plugin, NotificationOutbox.Notice notice) {
        NotificationService service = plugin.getServer().getServicesManager().load(NotificationService.class);
        if (service == null) return false;
        service.enqueueNotification(new TypedNotification<>("vaultstorage-" + notice.id(), notice.createdAt(), null,
                new NotificationTarget(List.of(notice.recipient())), MailPayload.DATA_TYPE,
                new MailPayload(notice.sender(), notice.senderName(),
                        net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(
                                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                                        .deserialize(notice.message()))), 0), false);
        return true;
    }
}
