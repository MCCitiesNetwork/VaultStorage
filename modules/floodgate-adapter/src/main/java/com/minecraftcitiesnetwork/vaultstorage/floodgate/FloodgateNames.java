package com.minecraftcitiesnetwork.vaultstorage.floodgate;

import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;

/**
 * Bedrock names through Floodgate, used when no primary name source is loaded. The only class
 * that names Floodgate's types.
 */
public final class FloodgateNames implements NameSource {

    private final @NotNull FloodgateApi floodgate;

    private FloodgateNames(@NotNull FloodgateApi floodgate) {
        this.floodgate = floodgate;
    }

    public static @NotNull NameSource create() {
        return new FloodgateNames(FloodgateApi.getInstance());
    }

    /** Whether a player is connected through Floodgate, including one with a linked Java account. */
    public static @NotNull Predicate<UUID> clientCheck() {
        return FloodgateApi.getInstance()::isFloodgatePlayer;
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
        // A Floodgate UUID carries the player's XUID in its low 64 bits.
        long xuid = player.getLeastSignificantBits();
        return this.floodgate.getGamertagFor(xuid)
                .thenApply(Optional::ofNullable)
                .exceptionally(failure -> Optional.empty());
    }

    @Override
    public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
        return this.floodgate.getUuidFor(name)
                .thenApply(Optional::ofNullable)
                .exceptionally(failure -> Optional.empty());
    }
}
