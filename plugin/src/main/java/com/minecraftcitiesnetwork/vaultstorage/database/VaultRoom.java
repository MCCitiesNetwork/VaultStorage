package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** A vault with free slots at its end, and the highest slot it uses (-1 when empty). */
public record VaultRoom(@NotNull UUID uuid, int highestSlot) {
}
