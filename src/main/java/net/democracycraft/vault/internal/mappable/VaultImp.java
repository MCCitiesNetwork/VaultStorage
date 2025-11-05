package net.democracycraft.vault.internal.mappable;

import net.democracycraft.vault.api.data.ItemDto;
import net.democracycraft.vault.api.data.VaultDto;
import net.democracycraft.vault.api.convertible.Vault;
import net.democracycraft.vault.internal.data.ItemDtoImp;
import net.democracycraft.vault.internal.data.LocationDtoImp;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Simple, in-memory Vault implementation.
 *
 * Adds container orientation support via the serialized BlockData string captured at time of vaulting.
 */
public record VaultImp(
        UUID ownerUniqueIdentifier,
        UUID uniqueIdentifier,
        List<ItemStack> contents,
        Material blockMaterial,
        Location blockLocation,
        Instant vaultedAt,
        String blockDataString
) implements Vault {

    /**
     * Constructs a Vault keeping a defensive copy of mutable fields and defaulting timestamps when null.
     *
     * @param ownerUniqueIdentifier owner UUID
     * @param uniqueIdentifier vault UUID
     * @param contents captured items (defensive-copied, null treated as empty)
     * @param blockMaterial original block material
     * @param blockLocation original block location (defensive-copied)
     * @param vaultedAt when the block was vaulted (defaults to now)
     * @param blockDataString serialized BlockData string representing orientation/state (nullable)
     */
    public VaultImp(UUID ownerUniqueIdentifier, UUID uniqueIdentifier, List<ItemStack> contents,
                    Material blockMaterial, Location blockLocation, Instant vaultedAt, String blockDataString) {
        this.ownerUniqueIdentifier = ownerUniqueIdentifier;
        this.uniqueIdentifier = uniqueIdentifier;
        this.contents = contents == null ? List.of() : new ArrayList<>(contents);
        this.blockMaterial = blockMaterial;
        this.blockLocation = blockLocation == null ? null : blockLocation.clone();
        this.vaultedAt = vaultedAt == null ? Instant.now() : vaultedAt;
        this.blockDataString = blockDataString;
    }

    @Override
    public VaultDto toDto() {
        List<ItemDto> itemDtos = new ArrayList<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getAmount() <= 0) continue;
            itemDtos.add(ItemDtoImp.fromItemStack(stack));
        }
        return new VaultDtoImp(uniqueIdentifier, ownerUniqueIdentifier, itemDtos,
                blockMaterial == null ? null : blockMaterial.name(),
                LocationDtoImp.fromLocation(blockLocation),
                vaultedAt == null ? Instant.now().toEpochMilli() : vaultedAt.toEpochMilli());
    }

    @Override
    public UUID getUniqueIdentifier() {
        return uniqueIdentifier;
    }
}
