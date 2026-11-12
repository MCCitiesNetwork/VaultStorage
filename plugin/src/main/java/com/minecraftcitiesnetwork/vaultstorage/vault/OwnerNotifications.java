package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tells a vault's owner that a container of theirs was vaulted. Builds the text here and hands
 * it to whichever {@link OwnerNotifier} is loaded; with none, the log says the owner was not told.
 */
public final class OwnerNotifications {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String NO_REGION = "N/A";

    private final @NotNull NameSource names;
    private final @NotNull Supplier<@Nullable OwnerNotifier> notifier;
    private final @NotNull Supplier<String> title;
    private final @NotNull Supplier<String> template;
    private final @NotNull Logger logger;

    /**
     * @param notifier read on every notification, because the module that supplies it starts
     *                 after the host and may stop while it runs
     * @param title    the notification's title, as MiniMessage
     * @param template the message, as MiniMessage with %sender%, %recipient% and %region%
     */
    public OwnerNotifications(@NotNull NameSource names, @NotNull Supplier<@Nullable OwnerNotifier> notifier,
                              @NotNull Supplier<String> title, @NotNull Supplier<String> template,
                              @NotNull Logger logger) {
        this.names = names;
        this.notifier = notifier;
        this.title = title;
        this.template = template;
        this.logger = logger;
    }

    /** Never completes exceptionally: a notification that fails is logged and nothing more. */
    public @NotNull CompletableFuture<Void> vaultCreated(@NotNull UUID owner, @NotNull UUID actor,
                                                        @Nullable String regionId) {
        return this.names.nameOf(actor)
                .thenCombine(this.names.nameOf(owner), (actorName, ownerName) -> {
                    // Names and the region id go in as text. Parsed as markup, a name could carry
                    // a click action that runs as whoever reads the message.
                    Component message = MINI_MESSAGE.deserialize(tags(this.template.get()),
                            Placeholder.unparsed("sender", actorName.orElse(shortId(actor))),
                            Placeholder.unparsed("recipient", ownerName.orElse(shortId(owner))),
                            Placeholder.unparsed("region", regionId == null ? NO_REGION : regionId));
                    deliver(owner, MINI_MESSAGE.deserialize(this.title.get()), message);
                    return (Void) null;
                })
                .exceptionally(failure -> {
                    this.logger.log(Level.WARNING, "Could not tell " + owner + " about their new vault.", failure);
                    return null;
                });
    }

    private void deliver(@NotNull UUID owner, @NotNull Component title, @NotNull Component message) {
        OwnerNotifier current = this.notifier.get();
        if (current == null) {
            this.logger.info("Vault owner " + owner + " was not told about their new vault:"
                    + " no notification module is loaded.");
            return;
        }
        current.deliver(owner, title, message);
    }

    /** The templates use %name% placeholders; MiniMessage's own are tags. */
    private static @NotNull String tags(@NotNull String template) {
        return template.replace("%sender%", "<sender>").replace("%recipient%", "<recipient>")
                .replace("%region%", "<region>");
    }

    private static @NotNull String shortId(@NotNull UUID player) {
        return player.toString().substring(0, 8);
    }
}
