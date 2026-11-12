package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.vault.Dto;

public interface DTOConvertible<T extends Dto> {
    T toDto();
}
