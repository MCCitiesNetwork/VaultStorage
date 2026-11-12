package com.minecraftcitiesnetwork.vaultstorage.names;

import com.minecraftcitiesnetwork.vaultstorage.Integrations;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/**
 * The name lookup host code calls. A module's primary source answers alone when one is loaded;
 * otherwise Paper answers, then a module's Bedrock source if Paper has nothing.
 *
 * <p>The slots are read on every call, because modules start after the host's services exist
 * and may stop while it runs.
 */
public final class Names implements NameSource {

    private final @NotNull NameSource paper;
    private final @NotNull Integrations integrations;

    public Names(@NotNull NameSource paper, @NotNull Integrations integrations) {
        this.paper = paper;
        this.integrations = integrations;
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
        return current().nameOf(player);
    }

    @Override
    public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
        return current().uuidOf(name);
    }

    private @NotNull NameSource current() {
        NameSource primary = this.integrations.primaryNames();
        if (primary != null) {
            return primary;
        }
        NameSource bedrock = this.integrations.bedrockNames();
        return bedrock == null ? this.paper : new FallbackNameSource(this.paper, bedrock);
    }
}
