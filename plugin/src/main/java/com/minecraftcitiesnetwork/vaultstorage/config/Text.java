package com.minecraftcitiesnetwork.vaultstorage.config;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;

/**
 * Renders a message from {@code messages.yml} for the classes that still reach the plugin
 * statically. Goes when those classes take a {@link MessageContainer} in their constructor.
 */
public final class Text {

    private Text() {
    }

    public static @NotNull Component of(@NotNull String key) {
        return messages().messageFor(key);
    }

    /**
     * @param placeholders name to value; a name may be wrapped in percent signs. Every value is
     *                     inserted as plain text, never parsed.
     */
    public static @NotNull Component of(@NotNull String key, @NotNull Map<String, String> placeholders) {
        return messages().messageFor(key, resolvers(placeholders));
    }

    /** The message without formatting, for a value that goes into another message. */
    public static @NotNull String plain(@NotNull String key) {
        return messages().plaintextMessageFor(key);
    }

    static TagResolver @NotNull [] resolvers(@NotNull Map<String, String> placeholders) {
        return placeholders.entrySet().stream()
                .map(entry -> MessageContainer.value(tagName(entry.getKey()),
                        entry.getValue() == null ? "" : entry.getValue()))
                .toArray(TagResolver[]::new);
    }

    /** {@code %reasonCode%} becomes {@code reason-code}: a tag name is lower case. */
    static @NotNull String tagName(@NotNull String placeholder) {
        String bare = placeholder.replace("%", "");
        StringBuilder name = new StringBuilder();
        for (char c : bare.toCharArray()) {
            if (Character.isUpperCase(c)) {
                name.append('-').append(Character.toLowerCase(c));
            } else {
                name.append(c);
            }
        }
        return name.toString();
    }

    private static @NotNull MessageContainer messages() {
        return VaultStoragePlugin.getInstance().messages();
    }
}
