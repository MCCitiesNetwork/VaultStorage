package com.minecraftcitiesnetwork.vaultstorage.names;

import com.destroystokyo.paper.profile.PlayerProfile;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Names through Paper's profile API: Java accounts only. A profile update answers from the
 * server's profile cache first and asks Mojang only when that misses, on Paper's own thread.
 */
public final class PaperProfileNameSource implements NameSource {

    private final @NotNull Function<UUID, CompletableFuture<PlayerProfile>> byId;
    private final @NotNull Function<String, CompletableFuture<PlayerProfile>> byName;

    public PaperProfileNameSource() {
        this(id -> Bukkit.createProfile(id).update(), name -> Bukkit.createProfile(name).update());
    }

    /** For tests: completes a profile without a running server. */
    public PaperProfileNameSource(@NotNull Function<UUID, CompletableFuture<PlayerProfile>> byId,
                                  @NotNull Function<String, CompletableFuture<PlayerProfile>> byName) {
        this.byId = byId;
        this.byName = byName;
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
        return this.byId.apply(player)
                .thenApply(profile -> Optional.ofNullable(profile).map(PlayerProfile::getName))
                .exceptionally(failure -> Optional.empty());
    }

    @Override
    public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
        CompletableFuture<PlayerProfile> lookup;
        try {
            lookup = this.byName.apply(name);
        } catch (IllegalArgumentException notAName) {
            // Too long, or characters no Java account can have.
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return lookup
                .thenApply(profile -> Optional.ofNullable(profile).map(PlayerProfile::getId))
                .exceptionally(failure -> Optional.empty());
    }
}
