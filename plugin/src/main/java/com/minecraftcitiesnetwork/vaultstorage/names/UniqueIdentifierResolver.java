package com.minecraftcitiesnetwork.vaultstorage.names;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Resolves what a player typed, a UUID or a name, to a UUID. */
public final class UniqueIdentifierResolver {

    private final @NotNull NameSource names;

    public UniqueIdentifierResolver(@NotNull NameSource names) {
        this.names = names;
    }


    /**
     * Resolves a UUID string or a player name to a UUID. A valid UUID string is returned as it
     * is, without a lookup.
     *
     * @return the UUID, or null when the name is unknown
     */
    public @NotNull CompletableFuture<UUID> resolveUuid(@NotNull String identifier) {
        UUID parsed = tryParseUUID(identifier);
        if (parsed != null) {
            return CompletableFuture.completedFuture(parsed);
        }
        return this.names.uuidOf(identifier).thenApply(found -> found.orElse(null));
    }

    private static @Nullable UUID tryParseUUID(@NotNull String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** Whether a UUID is usable: not null and not all zeros, which marks a failed conversion. */
    public static boolean isValidUUID(@Nullable UUID uuid) {
        if (uuid == null) {
            return false;
        }
        return !(uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L);
    }
}
