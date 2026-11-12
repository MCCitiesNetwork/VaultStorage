package net.democracycraft.vault.api.data;

import java.util.List;
import java.util.UUID;

public interface VaultDto extends Dto {

    UUID uniqueIdentifier();

    UUID owner();

    List<ItemDto> items();

    /** Original block material name (e.g., CHEST). */
    String material();

    /** Original block world location. */
    LocationDto location();

    /** Epoch milliseconds when it was vaulted. */
    long vaultedAt();
}
