package net.democracycraft.vault.internal.util.uuid;

import net.democracycraft.democracyLib.api.service.mojang.MojangService;
import net.democracycraft.vault.internal.session.BedrockUniqueIdentifierRetriever;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Utility for resolving player identifiers to UUIDs.
 * Supports both Java Edition (via MojangService) and Bedrock (via FloodgateApi).
 * Always tries Java first, then falls back to Bedrock.
 */
public class UniqueIdentifierResolver {

    private final MojangService<?> mojangService;
    private final BedrockUniqueIdentifierRetriever bedrockRetriever;

    public UniqueIdentifierResolver(@Nullable MojangService<?> mojangService, @Nullable BedrockUniqueIdentifierRetriever bedrockRetriever) {
        this.mojangService = mojangService;
        this.bedrockRetriever = bedrockRetriever;
    }

    /**
     * Attempts to resolve a player identifier (UUID string or player name) to a UUID.
     * If the input is already a valid UUID, returns it immediately.
     * Otherwise, tries to resolve as a player name (Java first, then Bedrock).
     *
     * @param identifier UUID string or player name
     * @return CompletableFuture with the resolved UUID, or null if resolution fails
     */
    public CompletableFuture<UUID> resolve(@NotNull String identifier) {
        // Try parsing as UUID first
        UUID parsed = tryParseUUID(identifier);
        if (parsed != null) {
            return CompletableFuture.completedFuture(parsed);
        }

        // Try resolving as player name
        return resolvePlayerName(identifier);
    }

    /**
     * Resolves a player name to UUID using MojangService (Java) then BedrockRetriever (Bedrock) as fallback.
     */
    private CompletableFuture<UUID> resolvePlayerName(@NotNull String playerName) {
        if (mojangService != null) {
            return mojangService.getUUID(playerName).thenCompose(resolved -> {
                if (resolved != null) {
                    return CompletableFuture.completedFuture(resolved);
                }
                // Fallback to Bedrock
                if (bedrockRetriever != null) {
                    return bedrockRetriever.getUniqueIdentifier(playerName)
                        .exceptionally(ex -> null);
                }
                return CompletableFuture.completedFuture(null);
            });
        } else if (bedrockRetriever != null) {
            return bedrockRetriever.getUniqueIdentifier(playerName)
                .exceptionally(ex -> null);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Tries to parse a string as UUID, returning null if it fails.
     */
    @Nullable
    private UUID tryParseUUID(@NotNull String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Checks if a UUID is valid (not null and not all zeros).
     * All-zero UUIDs (00000000-0000-0000-0000-000000000000) indicate failed conversions.
     */
    public static boolean isValidUUID(@Nullable UUID uuid) {
        if (uuid == null) return false;
        // Check if all zeros
        return !(uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L);
    }
}
