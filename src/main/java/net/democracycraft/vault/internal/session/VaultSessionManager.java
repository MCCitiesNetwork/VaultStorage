package net.democracycraft.vault.internal.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.democracycraft.vault.api.data.VaultDto;
import net.democracycraft.vault.internal.util.listener.DynamicListener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-player capture sessions and last resulting VaultDto.
 * Provides a dynamic listener per session and JSON serialization utility.
 */
public class VaultSessionManager {
    public static class Session {
        private final DynamicListener dynamicListener = new DynamicListener();
        private VaultDto lastVaultDto;

        public DynamicListener getDynamicListener() { return dynamicListener; }
        public VaultDto getLastVaultDto() { return lastVaultDto; }
        public void setLastVaultDto(VaultDto dto) { this.lastVaultDto = dto; }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public Session getOrCreate(UUID playerId) {
        return sessions.computeIfAbsent(playerId, id -> new Session());
    }

    public Session get(UUID playerId) { return sessions.get(playerId); }

    public void end(UUID playerId) {
        Session s = sessions.remove(playerId);
        if (s != null) s.getDynamicListener().close();
    }

    public String toJson(VaultDto dto) { return gson.toJson(dto); }
}
