package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * A hold on one vault, for whoever is editing, placing or deleting it. Only the holder can
 * give it back: a release names the token the claim was made with, and one that names another
 * token changes nothing.
 *
 * @param token made by the claim and stored on the vault's row
 */
public record Claim(@NotNull UUID vault, @NotNull UUID token) {
}
