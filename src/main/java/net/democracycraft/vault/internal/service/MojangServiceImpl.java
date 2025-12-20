package net.democracycraft.vault.internal.service;

import com.google.gson.*;
import net.democracycraft.vault.api.service.MojangService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the MojangService that securely resolves player usernames and UUIDs
 * using the official Mojang API.
 *
 * <p>Documentation:
 * <ul>
 *   <li>UUID lookup by username: https://api.mojang.com/users/profiles/minecraft/{username}</li>
 *   <li>Username lookup by UUID: https://sessionserver.mojang.com/session/minecraft/profile/{uuid}</li>
 *   <li>Name history: https://api.mojang.com/user/profiles/{uuid}/names</li>
 * </ul>
 *
 * <p>All requests use HTTPS and handle rate limiting gracefully.</p>
 */
public class MojangServiceImpl implements MojangService {

    private static final String UUID_TO_NAME_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String NAME_TO_UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String NAME_HISTORY_URL = "https://api.mojang.com/user/profiles/%s/names";

    private static final int TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(5);

    private final Map<UUID, String> usernameCache = new ConcurrentHashMap<>();

    @Override
    public String getUsername(UUID playerUuid) {
        if (playerUuid == null) return null;

        // Check cache first
        String cached = usernameCache.get(playerUuid);
        if (cached != null) return cached;

        try {
            String url = UUID_TO_NAME_URL + playerUuid.toString().replace("-", "");
            JsonObject response = getJsonObject(url);
            String name = response != null && response.has("name") ? response.get("name").getAsString() : null;

            if (name != null) {
                usernameCache.put(playerUuid, name); // cache for full runtime
            }
            return name;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    /**
     * Resolves the UUID for a given Minecraft username using Mojang's API.
     *
     * @param username target player's username
     * @return the UUID if found, or {@code null} if not found or invalid
     */
    public UUID getUUID(String username) {
        try {
            String url = NAME_TO_UUID_URL + username;
            JsonObject response = getJsonObject(url);
            if (response == null || !response.has("id")) return null;

            String rawUuid = response.get("id").getAsString();
            if (rawUuid.length() != 32) return null;
            return fromRawUUID(rawUuid);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns the complete username history of a player by UUID.
     *
     * @param playerUuid target player's UUID
     * @return list of all known usernames, or empty list if not available
     */
    public List<String> getNameHistory(UUID playerUuid) {
        List<String> names = new ArrayList<>();
        try {
            String url = String.format(NAME_HISTORY_URL, playerUuid.toString().replace("-", ""));
            JsonArray array = getJsonArray(url);
            if (array != null) {
                for (JsonElement element : array) {
                    JsonObject obj = element.getAsJsonObject();
                    if (obj.has("name")) {
                        names.add(obj.get("name").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return names;
    }

    /**
     * Checks if the given username exists (i.e., corresponds to a valid Mojang account).
     *
     * @param username target username
     * @return {@code true} if a valid account exists, otherwise {@code false}
     */
    public boolean doesPlayerExist(String username) {
        return getUUID(username) != null;
    }

    /**
     * Converts a raw UUID string (32 characters without dashes) into a {@link UUID}.
     *
     * @param rawUuid UUID in compact form
     * @return formatted UUID
     */
    private UUID fromRawUUID(String rawUuid) {
        String withDashes = rawUuid.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(withDashes);
    }

    /**
     * Fetches a JSON object from a given HTTPS endpoint.
     *
     * @param urlString target URL
     * @return parsed {@link JsonObject} or {@code null} if request fails
     * @throws IOException if the connection fails
     */
    private JsonObject getJsonObject(String urlString) throws IOException {
        HttpURLConnection connection = createConnection(urlString);
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            JsonElement element = JsonParser.parseReader(reader);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        }
    }

    /**
     * Fetches a JSON array from a given HTTPS endpoint.
     *
     * @param urlString target URL
     * @return parsed {@link JsonArray} or {@code null} if request fails
     * @throws IOException if the connection fails
     */
    private JsonArray getJsonArray(String urlString) throws IOException {
        HttpURLConnection connection = createConnection(urlString);
        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            JsonElement element = JsonParser.parseReader(reader);
            return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
        }
    }

    /**
     * Creates a properly configured HTTPS connection with timeout and headers.
     *
     * @param urlString the target URL
     * @return a configured {@link HttpURLConnection}
     * @throws IOException if connection setup fails
     */
    private HttpURLConnection createConnection(String urlString) throws IOException {
        URL url = URI.create(urlString).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "VaultStoragePlugin-MojangService/1.0");
        connection.setUseCaches(false);
        connection.setDoInput(true);
        return connection;
    }

    /**
     * Fetches the skin texture and signature for a given player UUID.
     *
     * @param playerUuid The UUID of the player
     * @return SkinData object containing value and signature, or null if not found.
     */
    public SkinData getSkin(UUID playerUuid) {
        if (playerUuid == null) return null;

        try {
            String url = UUID_TO_NAME_URL + playerUuid.toString().replace("-", "") + "?unsigned=false";

            JsonObject response = getJsonObject(url);

            if (response != null && response.has("properties")) {
                JsonArray properties = response.getAsJsonArray("properties");

                for (JsonElement element : properties) {
                    JsonObject prop = element.getAsJsonObject();
                    if (prop.has("name") && prop.get("name").getAsString().equals("textures")) {

                        String value = prop.get("value").getAsString();
                        String signature = prop.has("signature") ? prop.get("signature").getAsString() : null;

                        return new SkinData(value, signature);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static class SkinData {
        public final String value;
        public final String signature;

        public SkinData(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }
}
