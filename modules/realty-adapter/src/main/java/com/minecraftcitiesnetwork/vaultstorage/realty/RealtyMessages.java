package com.minecraftcitiesnetwork.vaultstorage.realty;

import com.minecraftcitiesnetwork.vaultstorage.config.ConfigFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;

/** The module's own {@code messages.yml}: what it says to players is its to word, not the host's. */
final class RealtyMessages {

    /** Told to a region's new occupant, once, when a sweep vaulted something. */
    static final String OCCUPANT_NOTIFIED = "occupant-notified";

    /** Not {@code messages.yml}: the module's class loader asks the host's first. */
    static final String BUNDLED = "realty-messages.yml";
    private static final String FILE = "messages.yml";

    private RealtyMessages() {
    }

    /** Reads the file, creating it the first time. A key the operator's file lacks takes the bundled wording. */
    static @NotNull ConfigurationNode load(@NotNull Path moduleFolder, @NotNull Logger logger) throws IOException {
        return new ConfigFiles(moduleFolder, logger)
                .loadFilled(FILE, ConfigFiles.bundled(RealtyMessages.class, BUNDLED));
    }
}
