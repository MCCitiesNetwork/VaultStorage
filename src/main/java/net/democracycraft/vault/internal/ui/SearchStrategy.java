package net.democracycraft.vault.internal.ui;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Analyzes search queries to determine if they are usernames, owner UUIDs, or vault UUIDs.
 * All operations work with strings to remain agnostic of the underlying type until database lookup.
 */
public class SearchStrategy {

    private static final int VARCHAR_MAX_LENGTH = 36;

    /**
     * Analyzes a search query string to determine its type.
     * Only validates if the string could be a varchar(36) UUID.
     *
     * @param query the search input string
     * @return SearchQuery with type and value (same as query if valid)
     */
    public static SearchQuery analyze(@NotNull String query) {
        // If it's exactly 36 chars, treat it as potential UUID (owner or vault)
        if (query.length() == VARCHAR_MAX_LENGTH) {
            return new SearchQuery(SearchType.UUID_STRING, query);
        }
        // Otherwise it's invalid or needs username resolution
        return new SearchQuery(SearchType.INVALID, null);
    }

    /**
     * Tries to parse and validate a username resolution result.
     * @param resolvedUUID the UUID returned from resolver
     * @return SearchQuery with USERNAME type if valid, otherwise INVALID
     */
    @Contract("_ -> new")
    public static @NotNull SearchQuery fromResolvedUsername(@Nullable UUID resolvedUUID) {
        if (isValidUUID(resolvedUUID)) {
            return new SearchQuery(SearchType.USERNAME, resolvedUUID.toString());
        }
        return new SearchQuery(SearchType.INVALID, null);
    }

    private static boolean isValidUUID(@Nullable UUID uuid) {
        if (uuid == null) return false;
        return !(uuid.getMostSignificantBits() == 0L && uuid.getLeastSignificantBits() == 0L);
    }

    public enum SearchType {
        USERNAME,
        UUID_STRING,
        INVALID
    }

    public record SearchQuery(@NotNull SearchType type, @Nullable String value) {}
}




