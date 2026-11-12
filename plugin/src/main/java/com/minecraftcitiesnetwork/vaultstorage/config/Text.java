package com.minecraftcitiesnetwork.vaultstorage.config;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;

/** Renders messages from {@code messages.yml}, with placeholders given as a map. */
public final class Text {

    private final @NotNull MessageContainer messages;

    public Text(@NotNull MessageContainer messages) {
        this.messages = messages;
    }

    public @NotNull Component of(@NotNull String key) {
        return this.messages.messageFor(key);
    }

    /**
     * @param placeholders name to value; a name may be wrapped in percent signs. Every value is
     *                     inserted as plain text, never parsed.
     */
    public @NotNull Component of(@NotNull String key, @NotNull Map<String, String> placeholders) {
        return this.messages.messageFor(key, resolvers(placeholders));
    }

    /** The message without formatting, for a label that goes into another message. */
    public @NotNull String plain(@NotNull String key) {
        return this.messages.plaintextMessageFor(key);
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
}
