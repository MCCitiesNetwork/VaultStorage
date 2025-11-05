package net.democracycraft.vault.internal.util.minimessage;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;

/**
 * Utility for safe MiniMessage parsing with graceful fallback to a plain text component.
 *
 * Contract:
 * - Input: a string that may contain MiniMessage tags.
 * - Output: a Component built by MiniMessage if parsing succeeds; otherwise, a plain text Component of the input.
 * - Placeholders: resolve with a simple Map-based string replacement before parsing.
 */
public final class MiniMessageUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private MiniMessageUtil() {}

    /**
     * Parses a text using MiniMessage. If parsing fails, returns a plain text {@link Component} with the same content.
     * @param text source string, possibly containing MiniMessage tags
     * @return parsed component or plain text component on error
     */
    public static Component parseOrPlain(String text) {
        if (text == null) {
            return Component.empty();
        }
        try {
            return MINI_MESSAGE.deserialize(text);
        } catch (Throwable parseError) {
            return Component.text(text);
        }
    }

    /**
     * Applies placeholders of the form %key% using a simple Map replacement, then parses with MiniMessage.
     * Falls back to plain text when MiniMessage parsing fails.
     * @param template template string that may contain placeholders like %player%
     * @param placeholders map of placeholder keys to values; may be null
     * @return parsed component or plain text component on error
     */
    public static Component parseOrPlain(String template, Map<String, String> placeholders) {
        if (template == null) {
            return Component.empty();
        }
        String resolved = template;
        if (placeholders != null && !placeholders.isEmpty()) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                String keyToken = entry.getKey();
                String valueText = entry.getValue() == null ? "" : entry.getValue();
                resolved = resolved.replace(keyToken, valueText);
            }
        }
        return parseOrPlain(resolved);
    }
}

