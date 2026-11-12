package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/** The values a vault's label is built from. Names no Bukkit type. */
public final class VaultLabel {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private VaultLabel() {
    }

    /** {@code TRAPPED_CHEST} as "Trapped Chest". */
    public static @NotNull String materialName(@NotNull String material) {
        StringBuilder name = new StringBuilder();
        for (String word : material.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return name.toString();
    }

    /** The day, in UTC, as {@code yyyy-MM-dd}. */
    public static @NotNull String date(@NotNull Instant time) {
        return DATE.format(time);
    }

    /** The message that says how many items a vault holds. */
    public static @NotNull String itemsKey(int count) {
        if (count <= 0) {
            return MessageKeys.LIST_ITEMS_NONE;
        }
        return count == 1 ? MessageKeys.LIST_ITEMS_ONE : MessageKeys.LIST_ITEMS_MANY;
    }
}
