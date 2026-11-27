package com.minecraftcitiesnetwork.vaultstorage.realty;

import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the Realty listener. It exists so the module's entry class never names
 * {@link RealtyOccupantChangeListener}, whose handler signatures name Realty's event types.
 */
public final class RealtyBinding {

    private RealtyBinding() {
    }

    public static @NotNull Listener createListener(@NotNull AutoVaultService service) {
        return new RealtyOccupantChangeListener(service);
    }
}
