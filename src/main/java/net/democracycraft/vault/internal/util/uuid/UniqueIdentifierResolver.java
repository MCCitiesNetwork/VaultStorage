package net.democracycraft.vault.internal.util.uuid;

import net.democracycraft.democracyLib.api.service.mojang.MojangService;
import net.democracycraft.vault.internal.session.BedrockUniqueIdentifierRetriever;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.spi.CollatorProvider;
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

    public UniqueIdentifierResolver(@NotNull MojangService<?> mojangService, @NotNull BedrockUniqueIdentifierRetriever bedrockRetriever) {
        this.mojangService = mojangService;
        this.bedrockRetriever = bedrockRetriever;
    }

    public @NotNull CompletableFuture<String> getName(@NotNull UUID uniqueIdentifier){
        return mojangService.getName(uniqueIdentifier).thenCompose(name -> {
            if (name != null){
                return CompletableFuture.completedFuture(name);
            }

            return bedrockRetriever.getPlayerName(uniqueIdentifier).
                    exceptionally(ex -> null);
        });
    }

    /**
     * Attempts to resolve a player identifier (UUID string or player name) to a UUID.
     * If the input is already a valid UUID, returns it immediately.
     * Otherwise, tries to resolve as a player name (Java first, then Bedrock).
     *
     * @param identifier UUID string or player name
     * @return CompletableFuture with the resolved UUID, or null if resolution fails
     */
    public CompletableFuture<UUID> resolveUuid(@NotNull String identifier) {
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
        return mojangService.getUUID(playerName).thenCompose(resolved -> {
            if (resolved != null) {
                return CompletableFuture.completedFuture(resolved);
            }
            // Fallback to Bedrock
            return bedrockRetriever.getUniqueIdentifier(playerName)
                .exceptionally(ex -> null);
        });
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
