package net.democracycraft.vault.internal.session;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.democracycraft.vault.api.data.VaultDto;
import net.democracycraft.vault.internal.util.listener.DynamicListener;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-player interaction sessions (capture/placement) and last resulting VaultDto.
 * <p>
 * Provides:
 * - A dynamic Bukkit listener container per player.
 * - A single, managed action bar task to avoid leaks across modes.
 * - A simple mode flag to enforce mutual exclusion between flows.
 */
public class VaultSessionManager {
    /** Current player session mode. */
    public enum Mode { NONE, CAPTURE, PLACEMENT }

    public static class Session {
        private final DynamicListener dynamicListener = new DynamicListener();
        private VaultDto lastVaultDto;
        private BukkitTask actionBarTask;
        private Mode mode = Mode.NONE;
        private long lastScanTime = 0;

        /** Returns the dynamic listener holder for this session. */
        public DynamicListener getDynamicListener() { return dynamicListener; }
        /** Returns the last created or interacted vault DTO for convenience. */
        public VaultDto getLastVaultDto() { return lastVaultDto; }
        public void setLastVaultDto(VaultDto dto) { this.lastVaultDto = dto; }

        /** Returns current session mode. */
        public Mode getMode() { return mode; }

        /**
         * Switches to a new mode, stopping any active listener and cancelling existing action bar task.
         * Use this before starting a new capture/placement flow to ensure mutual exclusion.
         */
        public void switchTo(Mode newMode) {
            if (this.mode == newMode) return;
            // Stop listener and clear actionbar to avoid overlap
            dynamicListener.stop();
            clearActionBarTask();
            this.mode = newMode;
        }

        /** Registers the action bar task, cancelling a previous one if present. */
        public void setActionBarTask(org.bukkit.scheduler.BukkitTask task) {
            clearActionBarTask();
            this.actionBarTask = task;
        }

        /** Cancels and clears the current action bar task if any. */
        public void clearActionBarTask() {
            if (this.actionBarTask != null) {
                try { this.actionBarTask.cancel(); } catch (Throwable ignored) {}
                this.actionBarTask = null;
            }
        }

        public long getLastScanTime() { return lastScanTime; }
        public void setLastScanTime(long time) { this.lastScanTime = time; }
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public Session getOrCreate(UUID playerId) {
        return sessions.computeIfAbsent(playerId, id -> new Session());
    }

    public Session get(UUID playerId) { return sessions.get(playerId); }

    public void end(UUID playerId) {
        Session s = sessions.remove(playerId);
        if (s != null) {
            s.getDynamicListener().close();
            s.clearActionBarTask();
        }
    }

    public String toJson(VaultDto dto) { return gson.toJson(dto); }
}
