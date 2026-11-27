package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OwnerNotificationsTest {

    private static final UUID OWNER = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
    private static final String TEMPLATE =
            "<gold><sender></gold> created a new vault for you: <yellow><recipient></yellow> in region <yellow><region></yellow>";

    private record Delivered(UUID recipient, String title, String message) {
    }

    private final List<Delivered> delivered = new ArrayList<>();
    private OwnerNotifier notifier = (recipient, title, message) -> this.delivered.add(new Delivered(recipient,
            PlainTextComponentSerializer.plainText().serialize(title),
            PlainTextComponentSerializer.plainText().serialize(message)));

    private OwnerNotifications notifications(Map<UUID, String> knownNames) {
        NameSource names = new NameSource() {
            @Override
            public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
                return CompletableFuture.completedFuture(Optional.ofNullable(knownNames.get(player)));
            }

            @Override
            public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
        MessageContainer messages = new MessageContainer();
        messages.setMessage(MessageKeys.NOTIFICATION_TITLE, "<gold>Vault storage</gold>");
        messages.setMessage(MessageKeys.NOTIFICATION_VAULT_CREATED, TEMPLATE);
        messages.setMessage(MessageKeys.NOTIFICATION_SWEEP_VAULTED,
                "<gold><count></gold> of your containers in <yellow><region></yellow> were vaulted for <gold><sender></gold>");
        return new OwnerNotifications(names, () -> this.notifier, messages, Logger.getLogger("test"));
    }

    @Test
    void theOwnerIsToldWhoVaultedTheirContainerAndWhere() {
        notifications(Map.of(OWNER, "Bob", ACTOR, "Alice")).vaultCreated(OWNER, ACTOR, "plot1").join();
        Assertions.assertEquals(List.of(new Delivered(OWNER, "Vault storage",
                "Alice created a new vault for you: Bob in region plot1")), this.delivered);
    }

    @Test
    void anUnknownNameIsShownAsAShortIdAndAMissingRegionAsNotApplicable() {
        notifications(Map.of(OWNER, "Bob")).vaultCreated(OWNER, ACTOR, null).join();
        Assertions.assertEquals("dddddddd created a new vault for you: Bob in region N/A",
                this.delivered.getFirst().message());
    }

    @Test
    void aNameIsInsertedAsTextNotAsMarkup() {
        notifications(Map.of(OWNER, "Bob", ACTOR, "<red>Mallory")).vaultCreated(OWNER, ACTOR, "plot1").join();
        Assertions.assertTrue(this.delivered.getFirst().message().startsWith("<red>Mallory created"));
    }

    @Test
    void aSweepTellsTheOwnerOnceHowManyContainersWereVaulted() {
        notifications(Map.of(OWNER, "Bob", ACTOR, "Alice")).sweepVaulted(OWNER, ACTOR, 12, "plot1").join();
        Assertions.assertEquals(List.of(new Delivered(OWNER, "Vault storage",
                "12 of your containers in plot1 were vaulted for Alice")), this.delivered);
    }

    @Test
    void aSweepThatVaultedNothingTellsNobody() {
        notifications(Map.of(OWNER, "Bob", ACTOR, "Alice")).sweepVaulted(OWNER, ACTOR, 0, "plot1").join();
        Assertions.assertTrue(this.delivered.isEmpty());
    }

    @Test
    void aNotifierThatFailsDoesNotFailTheCaller() {
        this.notifier = (recipient, title, message) -> {
            throw new IllegalStateException("notifications down");
        };
        CompletableFuture<Void> result = notifications(Map.of(OWNER, "Bob", ACTOR, "Alice"))
                .vaultCreated(OWNER, ACTOR, "plot1");
        result.join();
        Assertions.assertFalse(result.isCompletedExceptionally());
    }

    @Test
    void componentsReachTheNotifierWithTheirFormatting() {
        List<Component> messages = new ArrayList<>();
        this.notifier = (recipient, title, message) -> messages.add(message);
        notifications(Map.of(OWNER, "Bob", ACTOR, "Alice")).vaultCreated(OWNER, ACTOR, "plot1").join();
        Assertions.assertNotEquals(Component.text("Alice created a new vault for you: Bob in region plot1"),
                messages.getFirst());
    }
}
