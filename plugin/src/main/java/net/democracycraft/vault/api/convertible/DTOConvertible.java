package net.democracycraft.vault.api.convertible;

import net.democracycraft.vault.api.data.Dto;

public interface DTOConvertible<T extends Dto> {
    T toDto();
}
