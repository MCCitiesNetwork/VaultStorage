package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Where appended items went. Kept by the caller so that the append can be undone.
 *
 * @param vault     the vault that now holds the items
 * @param firstSlot the slot of the first appended item
 * @param slots     how many slots the append spans
 * @param created   whether no vault had room, so that this one was created for the items.
 *                  Nobody holds it: a later append may join it.
 */
public record AppendResult(@NotNull UUID vault, int firstSlot, int slots, boolean created) {
}
