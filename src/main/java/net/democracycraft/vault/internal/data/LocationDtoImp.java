package net.democracycraft.vault.internal.data;

import net.democracycraft.vault.api.data.LocationDto;
import org.bukkit.Location;

/**
 * Immutable implementation of LocationDto.
 */
public record LocationDtoImp(String world, int x, int y, int z) implements LocationDto {

    public static LocationDto fromLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return new LocationDtoImp(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}

