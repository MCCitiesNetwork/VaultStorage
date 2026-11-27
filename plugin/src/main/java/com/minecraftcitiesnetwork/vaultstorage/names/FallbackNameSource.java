package com.minecraftcitiesnetwork.vaultstorage.names;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/** Asks a first source, then a second when the first has no answer. */
public final class FallbackNameSource implements NameSource {

    private final @NotNull NameSource first;
    private final @NotNull NameSource second;

    public FallbackNameSource(@NotNull NameSource first, @NotNull NameSource second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
        return this.first.nameOf(player).thenCompose(found ->
                found.isPresent() ? CompletableFuture.completedFuture(found) : this.second.nameOf(player));
    }

    @Override
    public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
        return this.first.uuidOf(name).thenCompose(found ->
                found.isPresent() ? CompletableFuture.completedFuture(found) : this.second.uuidOf(name));
    }
}
