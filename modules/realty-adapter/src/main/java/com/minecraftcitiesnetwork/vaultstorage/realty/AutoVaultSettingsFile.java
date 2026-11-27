package com.minecraftcitiesnetwork.vaultstorage.realty;

import com.minecraftcitiesnetwork.vaultstorage.config.ConfigFiles;
import com.minecraftcitiesnetwork.vaultstorage.config.LegacyConfigImport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;

/** Reads the module's {@code config.yml}, creating it the first time. */
final class AutoVaultSettingsFile {

    /** Not {@code config.yml}: the module's class loader asks the host's first. */
    static final String BUNDLED = "realty.yml";
    private static final String FILE = "config.yml";

    private AutoVaultSettingsFile() {
    }

    /**
     * @param moduleFolder the module's data folder
     * @param hostFolder   the host's data folder, where the 1.x {@code config.yml} may still be
     */
    static @NotNull AutoVaultSettings load(@NotNull Path moduleFolder, @NotNull Path hostFolder,
                                           @NotNull Logger logger) throws IOException {
        Map<String, String> carried = carriedOver(new ConfigFiles(hostFolder, logger).legacy());
        String bundled = ConfigFiles.bundled(AutoVaultSettingsFile.class, BUNDLED);
        AutoVaultSettings settings = new ConfigFiles(moduleFolder, logger)
                .load(FILE, bundled, text -> LegacyConfigImport.fill(text, carried))
                .get(AutoVaultSettings.class);
        if (settings == null) {
            throw new IOException(FILE + " is empty");
        }
        return settings;
    }

    /** The 1.x {@code auto-vault.*} values under their new keys. */
    static @NotNull Map<String, String> carriedOver(@NotNull ConfigurationNode old) {
        Map<String, String> values = new HashMap<>();
        ConfigurationNode section = old.node("auto-vault");
        for (String key : new String[] {"enabled", "include-hangings", "notify-occupant"}) {
            if (!section.node(key).virtual()) {
                values.put(key, String.valueOf(section.node(key).getBoolean()));
            }
        }
        if (!section.node("delay-ticks").virtual()) {
            values.put("delay-seconds",
                    String.valueOf(LegacyConfigImport.ticksToSeconds(section.node("delay-ticks").getLong())));
        }
        return values;
    }
}
