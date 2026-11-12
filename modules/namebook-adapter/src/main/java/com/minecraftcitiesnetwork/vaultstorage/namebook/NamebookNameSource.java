package com.minecraftcitiesnetwork.vaultstorage.namebook;

import com.minecraftcitiesnetwork.namebook.api.Namebook;
import com.minecraftcitiesnetwork.namebook.api.NamebookFinder;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.ServicesManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Names through Namebook, which covers Java and Bedrock players. The only class that names
 * Namebook's types. Namebook's futures never complete exceptionally, which NameSource requires.
 */
public final class NamebookNameSource implements NameSource {

    private final @NotNull Namebook namebook;

    private NamebookNameSource(@NotNull Namebook namebook) {
        this.namebook = namebook;
    }

    /** Null when Namebook is installed but has not registered its service. */
    public static @Nullable NameSource find(@NotNull ServicesManager services) {
        return NamebookFinder.find(services).map(NamebookNameSource::new).orElse(null);
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
        return this.namebook.nameOf(player);
    }

    @Override
    public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
        return this.namebook.uuidOf(name);
    }
}
