package net.democracycraft.vault.internal.data;

import net.democracycraft.vault.api.data.ItemDto;
import net.democracycraft.vault.api.data.LocationDto;
import net.democracycraft.vault.api.data.VaultDto;

import java.util.List;
import java.util.UUID;

/**
 * Simple immutable implementation of VaultDto.
 */
public record VaultDtoImp(UUID uniqueIdentifier, UUID owner, List<ItemDto> items,
                          String material, LocationDto location, long vaultedAt) implements VaultDto {
    public VaultDtoImp(UUID uniqueIdentifier, UUID owner, List<ItemDto> items,
                       String material, LocationDto location, long vaultedAt) {
        this.uniqueIdentifier = uniqueIdentifier;
        this.owner = owner;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.material = material == null ? "" : material;
        this.location = location;
        this.vaultedAt = vaultedAt;
    }
}
