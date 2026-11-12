package com.minecraftcitiesnetwork.vaultstorage.vault;

import org.bukkit.inventory.ItemStack;

public interface ItemDto extends Dto {

    int amount();

    byte[] bytes();

    ItemStack getItemStack();
}
