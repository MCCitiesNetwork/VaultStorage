package com.minecraftcitiesnetwork.vaultstorage.config;

import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Carries values from the 1.x {@code config.yml} into the files that replace it, the first time
 * the new files are created. Works on text, so the bundled file's comments survive.
 */
public final class LegacyConfigImport {

    private static final int TICKS_PER_SECOND = 20;

    private LegacyConfigImport() {
    }

    /** The old host, port and database name as one address, without {@code jdbc:} in front. */
    public static @NotNull String databaseUrl(@NotNull String host, int port, @NotNull String database, boolean useSsl) {
        return "mariadb://" + host + ":" + port + "/" + database + (useSsl ? "?sslMode=trust" : "");
    }

    /** Whole seconds for a delay that was configured in ticks, rounded up. */
    public static long ticksToSeconds(long ticks) {
        return ticks <= 0 ? 0 : (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }

    /**
     * Replaces values in a bundled default.
     *
     * @param bundled the file's text, two-space indented, at most one level of nesting
     * @param values  dotted key to new value. A value that is not a number or a boolean is
     *                written as a double-quoted YAML string.
     */
    public static @NotNull String fill(@NotNull String bundled, @NotNull Map<String, String> values) {
        if (values.isEmpty()) {
            return bundled;
        }
        StringBuilder out = new StringBuilder();
        String section = "";
        for (String line : bundled.split("\n", -1)) {
            String trimmed = line.stripLeading();
            int colon = trimmed.indexOf(':');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || colon < 0) {
                out.append(line).append('\n');
                continue;
            }
            boolean nested = line.startsWith("  ");
            String key = trimmed.substring(0, colon);
            if (!nested) {
                // A top-level key with nothing after the colon opens a section.
                section = trimmed.substring(colon + 1).isBlank() ? key : "";
            }
            String path = nested && !section.isEmpty() ? section + "." + key : key;
            String replacement = values.get(path);
            if (replacement == null) {
                out.append(line).append('\n');
            } else {
                out.append(nested ? "  " : "").append(key).append(": ").append(scalar(replacement)).append('\n');
            }
        }
        // split(-1) yields a final empty piece after the last newline.
        out.setLength(out.length() - 1);
        return out.toString();
    }

    private static @NotNull String scalar(@NotNull String value) {
        if (value.matches("-?\\d+") || value.equals("true") || value.equals("false")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
