package com.minecraftcitiesnetwork.vaultstorage.autovault;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/** The module's {@code config.yml}. */
@ConfigSerializable
public record AutoVaultSettings(@Setting("enabled") boolean enabled,
                                @Setting("delay-seconds") long delaySeconds,
                                @Setting("include-hangings") boolean includeHangings,
                                @Setting("notify-occupant") boolean notifyOccupant) {

    private static final long TICKS_PER_SECOND = 20L;

    public AutoVaultSettings {
        if (delaySeconds < 0) {
            throw new IllegalArgumentException("delay-seconds must not be negative, but is " + delaySeconds);
        }
    }

    public long delayTicks() {
        return this.delaySeconds * TICKS_PER_SECOND;
    }
}
