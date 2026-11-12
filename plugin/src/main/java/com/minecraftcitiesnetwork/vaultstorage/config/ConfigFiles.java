package com.minecraftcitiesnetwork.vaultstorage.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Loads an operator's YAML file from a folder.
 *
 * <p>The file is created from the bundled default when it is absent and never written again:
 * saving through a YAML library would strip the operator's comments and reorder their keys. The
 * bundled default is written beside it, under {@code defaults/}, on every start, so an operator
 * can see what a new version added with a diff.
 */
public final class ConfigFiles {

    private final @NotNull Path folder;
    private final @NotNull Logger logger;

    public ConfigFiles(@NotNull Path folder, @NotNull Logger logger) {
        this.folder = folder;
        this.logger = logger;
    }

    /** The text of a file bundled in the jar that holds {@code owner}. */
    public static @NotNull String bundled(@NotNull Class<?> owner, @NotNull String resource) throws IOException {
        try (InputStream stream = owner.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("No bundled " + resource + " in the jar");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Whether the operator's file is there yet. */
    public boolean exists(@NotNull String fileName) {
        return Files.exists(this.folder.resolve(fileName));
    }

    /**
     * @param fileName      the operator's file, relative to the folder
     * @param bundledText   the default from the jar
     * @param whenCreating  applied to the default the one time the operator's file is created;
     *                      the identity unless values are being carried over from an old file
     */
    public @NotNull ConfigurationNode load(@NotNull String fileName, @NotNull String bundledText,
                                           @NotNull UnaryOperator<String> whenCreating) throws IOException {
        Path live = this.folder.resolve(fileName);
        Path reference = this.folder.resolve("defaults").resolve(fileName);
        Files.createDirectories(reference.getParent());
        Files.writeString(reference, bundledText, StandardCharsets.UTF_8);
        if (Files.notExists(live)) {
            Files.createDirectories(live.getParent());
            Files.writeString(live, whenCreating.apply(bundledText), StandardCharsets.UTF_8);
        }
        ConfigurationNode operator = loader().path(live).build().load();
        ConfigurationNode defaults = loader().path(reference).build().load();
        List<String> missing = ConfigKeyGaps.missingKeys(defaults, operator);
        if (!missing.isEmpty()) {
            this.logger.warning(fileName + " is missing keys added in this version: " + missing
                    + ". See defaults/" + fileName + ".");
        }
        return operator;
    }

    /** The 1.x {@code config.yml}, or an empty node when there is none or it does not parse. */
    public @NotNull ConfigurationNode legacy() {
        Path old = this.folder.resolve("config.yml");
        try {
            if (Files.exists(old)) {
                return loader().path(old).build().load();
            }
        } catch (IOException ex) {
            this.logger.warning("The old config.yml could not be read, so nothing is carried over from it: "
                    + ex.getMessage());
        }
        return loader().build().createNode();
    }

    private static YamlConfigurationLoader.Builder loader() {
        return YamlConfigurationLoader.builder().nodeStyle(NodeStyle.BLOCK);
    }
}
