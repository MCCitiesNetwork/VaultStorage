package com.minecraftcitiesnetwork.vaultstorage.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Finds the keys the bundled default has and an operator's file lacks. Without it, a key added
 * in an upgrade arrives silently as 0 or false.
 */
public final class ConfigKeyGaps {

    private ConfigKeyGaps() {
    }

    /** Dotted paths of the missing leaves, sorted. A list counts as one leaf. */
    public static @NotNull List<String> missingKeys(@NotNull ConfigurationNode bundled,
                                                    @NotNull ConfigurationNode operator) {
        List<String> missing = new ArrayList<>();
        collect(bundled, operator, missing);
        missing.sort(null);
        return missing;
    }

    private static void collect(@NotNull ConfigurationNode bundled, @NotNull ConfigurationNode operator,
                                @NotNull List<String> missing) {
        if (!bundled.isMap()) {
            if (operator.virtual()) {
                missing.add(Arrays.stream(bundled.path().array()).map(String::valueOf)
                        .collect(Collectors.joining(".")));
            }
            return;
        }
        for (ConfigurationNode child : bundled.childrenMap().values()) {
            collect(child, operator.node(child.key()), missing);
        }
    }
}
