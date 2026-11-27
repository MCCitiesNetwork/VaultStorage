package com.minecraftcitiesnetwork.vaultstorage.config;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MessageKeysTest {

    @Test
    void everyDeclaredKeyIsPresentInTheShippedFile() throws IllegalAccessException {
        MessageContainer messages = TestMessages.shipped();
        for (String key : declaredKeys()) {
            Assertions.assertNotEquals(key, messages.miniMessageFormattedFor(key),
                    "MessageKeys declares '" + key + "' but messages.yml has no such key");
        }
    }

    @Test
    void everyShippedKeyHasADeclaredConstant() throws IllegalAccessException {
        Set<String> declared = new HashSet<>(declaredKeys());
        for (String key : TestMessages.shippedKeys()) {
            Assertions.assertTrue(declared.contains(key),
                    "messages.yml defines '" + key + "' but MessageKeys has no constant for it");
        }
    }

    @Test
    void aConstantIsNamedAfterItsKey() throws IllegalAccessException {
        for (Field field : MessageKeys.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                String key = (String) field.get(null);
                Assertions.assertEquals(key.replace('.', '_').replace('-', '_').toUpperCase(), field.getName());
            }
        }
    }

    @Test
    void aValueAPlayerChoseIsShownAsTextAndNeverParsed() {
        String rendered = PlainTextComponentSerializer.plainText().serialize(
                TestMessages.shipped().messageFor(MessageKeys.COMMAND_TRANSFER_UNKNOWN_PLAYER,
                        Text.resolvers(Map.of("player", "<click:run_command:/op me>x"))));

        // The markup the player typed is shown as they typed it, whatever the sentence around it says.
        Assertions.assertTrue(rendered.contains("<click:run_command:/op me>x"), rendered);
    }

    @Test
    void anOldPercentPlaceholderNameBecomesATagName() {
        Assertions.assertEquals("owner", Text.tagName("%owner%"));
        Assertions.assertEquals("reason-code", Text.tagName("%reasonCode%"));
        Assertions.assertEquals("region", Text.tagName("region"));
    }

    @Test
    void theCaptureActionBarSaysWhyABlockCannotBeVaulted() {
        MessageContainer messages = TestMessages.shipped();
        String rendered = PlainTextComponentSerializer.plainText().serialize(messages.messageFor(
                MessageKeys.CAPTURE_ACTION_BAR_NOT_VAULTABLE,
                MessageContainer.value("owner", "Alice"),
                MessageContainer.markup("reason-segment", messages.messageFor(
                        MessageKeys.CAPTURE_ACTION_BAR_REASON_SEGMENT_TEMPLATE,
                        MessageContainer.markup("reason", messages.messageFor(MessageKeys.CAPTURE_REASON_NOT_IN_REGION)))),
                MessageContainer.markup("admin", messages.messageFor(MessageKeys.CAPTURE_ACTION_BAR_ADMIN_MODE_TAG))));

        Assertions.assertEquals(
                "Owner: Alice | Vaultable: no | Reason: block is not inside any protected region |  ADMIN", rendered);
    }

    @Test
    void theCaptureActionBarForAVaultableBlockHasNoReason() {
        String rendered = PlainTextComponentSerializer.plainText().serialize(TestMessages.shipped().messageFor(
                MessageKeys.CAPTURE_ACTION_BAR_VAULTABLE,
                MessageContainer.value("owner", "Alice"), MessageContainer.markup("admin", Component.empty())));

        Assertions.assertEquals("Owner: Alice | Vaultable: yes", rendered);
    }

    @Test
    void aVaultsLabelSaysWhatItIsWhereItStoodAndWhatItHolds() {
        MessageContainer messages = TestMessages.shipped();
        String rendered = PlainTextComponentSerializer.plainText().serialize(messages.messageFor(
                MessageKeys.LIST_ITEM_FORMAT,
                MessageContainer.value("owner", "Alice"), MessageContainer.value("index", "3"),
                MessageContainer.value("material", "Trapped Chest"), MessageContainer.value("world", "world"),
                MessageContainer.value("x", "10"), MessageContainer.value("y", "64"), MessageContainer.value("z", "-5"),
                MessageContainer.markup("items", messages.messageFor(MessageKeys.LIST_ITEMS_MANY,
                        MessageContainer.value("count", "12"))),
                MessageContainer.value("date", "2026-11-12")));

        Assertions.assertEquals(
                "Alice's Vault #3 | Trapped Chest at world 10, 64, -5 | 12 items | 2026-11-12", rendered);
    }

    private static List<String> declaredKeys() throws IllegalAccessException {
        List<String> keys = new ArrayList<>();
        for (Field field : MessageKeys.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                keys.add((String) field.get(null));
            }
        }
        Assertions.assertTrue(keys.size() > 100, "reflection found only " + keys.size() + " keys; the walk is broken");
        return keys;
    }
}
