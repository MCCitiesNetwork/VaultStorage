package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Tells a vault's owner that a container of theirs was vaulted. Builds the text here and hands
 * it to whichever {@link OwnerNotifier} is loaded; with none, the log says the owner was not told.
 */
public final class OwnerNotifications {

    private static final String NO_REGION = "N/A";

    private final @NotNull NameSource names;
    private final @NotNull Supplier<@Nullable OwnerNotifier> notifier;
    private final @NotNull MessageContainer messages;
    private final @NotNull Logger logger;

    /**
     * @param notifier read on every notification, because the module that supplies it starts
     *                 after the host and may stop while it runs
     * @param messages holds the title and the two sentences
     */
    public OwnerNotifications(@NotNull NameSource names, @NotNull Supplier<@Nullable OwnerNotifier> notifier,
                              @NotNull MessageContainer messages, @NotNull Logger logger) {
        this.names = names;
        this.notifier = notifier;
        this.messages = messages;
        this.logger = logger;
    }

    /** Never completes exceptionally: a notification that fails is logged and nothing more. */
    public @NotNull CompletableFuture<Void> vaultCreated(@NotNull UUID owner, @NotNull UUID actor,
                                                        @Nullable String regionId) {
        return notify(owner, actor, regionId, MessageKeys.NOTIFICATION_VAULT_CREATED, null);
    }

    /**
     * One message for a whole sweep, however many of the owner's containers it vaulted. Does
     * nothing when it vaulted none. Never completes exceptionally.
     */
    public @NotNull CompletableFuture<Void> sweepVaulted(@NotNull UUID owner, @NotNull UUID newOccupant, int containers,
                                                        @Nullable String regionId) {
        if (containers <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        return notify(owner, newOccupant, regionId, MessageKeys.NOTIFICATION_SWEEP_VAULTED, containers);
    }

    private @NotNull CompletableFuture<Void> notify(@NotNull UUID owner, @NotNull UUID actor, @Nullable String regionId,
                                                   @NotNull String key, @Nullable Integer count) {
        return this.names.nameOf(actor)
                .thenCombine(this.names.nameOf(owner), (actorName, ownerName) -> {
                    // Names and the region id go in as text. Parsed as markup, a name could carry
                    // a click action that runs as whoever reads the message.
                    Component message = this.messages.messageFor(key,
                            MessageContainer.value("count", count == null ? "" : String.valueOf(count)),
                            MessageContainer.value("sender", actorName.orElse(shortId(actor))),
                            MessageContainer.value("recipient", ownerName.orElse(shortId(owner))),
                            MessageContainer.value("region", regionId == null ? NO_REGION : regionId));
                    deliver(owner, this.messages.messageFor(MessageKeys.NOTIFICATION_TITLE), message);
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

    private static @NotNull String shortId(@NotNull UUID player) {
        return player.toString().substring(0, 8);
    }
}
