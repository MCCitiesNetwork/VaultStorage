package net.democracycraft.vault.api.convertible;

import net.democracycraft.vault.api.data.VaultDto;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface Vault extends DTOConvertible<VaultDto> {

    UUID getUniqueIdentifier();

    UUID ownerUniqueIdentifier();

    UUID uniqueIdentifier();

    List<ItemStack> contents();

    /** Material of the original block converted into this vault. */
    Material blockMaterial();

    /** World location of the original block. */
    Location blockLocation();

    /** Timestamp when the block was vaulted. */
    Instant vaultedAt();

    boolean isContainer();
}
