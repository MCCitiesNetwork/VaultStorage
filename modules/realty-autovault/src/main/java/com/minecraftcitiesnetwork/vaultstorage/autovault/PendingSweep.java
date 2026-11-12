package com.minecraftcitiesnetwork.vaultstorage.autovault;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A sweep waiting to run: which region, for which new occupant, and when. Stored, so a restart
 * during the grace period does not lose it.
 */
public record PendingSweep(@NotNull UUID worldUuid, @NotNull String regionId, @NotNull UUID initiatorUuid,
                           @NotNull Instant dueTime) {

    /** How long after startup a sweep that is already due waits, so the server has settled. */
    public static final long OVERDUE_DELAY_TICKS = 100L;

    private static final long MILLIS_PER_TICK = 50L;

    public PendingSweep {
        // WorldGuard keeps region ids in lower case.
        regionId = regionId.toLowerCase(Locale.ROOT);
    }

    /** Ticks from {@code now} until the sweep is due, rounded up; a short delay if it already is. */
    public long ticksUntilDue(@NotNull Instant now) {
        long millis = Duration.between(now, this.dueTime).toMillis();
        if (millis <= 0) {
            return OVERDUE_DELAY_TICKS;
        }
        return (millis + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK;
    }
}
