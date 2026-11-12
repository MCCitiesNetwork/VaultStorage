package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** The two writes a capture needs, so a capture can be tested without a database. */
public interface VaultStore {

    /** Creates a vault and its items together: either all of it is stored or none of it. */
    @NotNull UUID create(@NotNull NewVault vault, @NotNull List<VaultItem> items);

    /** Deletes a vault; its items go with it. */
    @NotNull DeleteResult delete(@NotNull UUID vault);
}
