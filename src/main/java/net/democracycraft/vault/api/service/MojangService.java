package net.democracycraft.vault.api.service;

import java.util.List;
import java.util.UUID;

/**
 * Service for securely resolving Minecraft player data
 * using the official Mojang API.
 *
 * <p>This service provides methods to look up player usernames,
 * UUIDs, and historical name data.</p>
 *
 * <p>All lookups are performed using Mojang's public HTTPS APIs:</p>
 * <ul>
 *   <li>UUID lookup by username: https://api.mojang.com/users/profiles/minecraft/{username}</li>
 *   <li>Username lookup by UUID: https://sessionserver.mojang.com/session/minecraft/profile/{uuid}</li>
 *   <li>Name history: https://api.mojang.com/user/profiles/{uuid}/names</li>
 * </ul>
 */
public interface MojangService extends Service {

    /**
     * Resolves the current username for the given player UUID.
     *
     * @param playerUuid target player UUID
     * @return the current username, or {@code null} if not found
     */
    String getUsername(UUID playerUuid);

    /**
     * Resolves the UUID for a given Minecraft username.
     *
     * @param username target player's username
     * @return the UUID if found, or {@code null} if not found
     */
    UUID getUUID(String username);

    /**
     * Returns the complete name history for a player.
     *
     * @param playerUuid target player UUID
     * @return list of all known usernames (most recent last),
     *         or an empty list if not available
     */
    List<String> getNameHistory(UUID playerUuid);

    /**
     * Checks whether a given username corresponds to a valid Mojang account.
     *
     * @param username target player's username
     * @return {@code true} if the player exists, otherwise {@code false}
     */
    boolean doesPlayerExist(String username);

    default boolean hasBeenNamed(UUID playerUuid, String username) {
        List<String> nameHistory = getNameHistory(playerUuid);
        for (String name : nameHistory) {
            if (name.equalsIgnoreCase(username)) {
                return true;
            }
        }
        return false;
    }
}
