package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import org.jetbrains.annotations.NotNull;

/** One shop in the registry: its sign and its owner. */
public record Shop(@NotNull ShopPosition sign, @NotNull ShopOwner owner) {
}
