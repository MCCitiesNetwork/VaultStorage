package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Works out whose a shop container is from the registry. Names no Bukkit type. */
public final class ShopOwners {

    private ShopOwners() {
    }

    /**
     * The player every sign on a container belongs to.
     *
     * @param registry the shops the registry lists around the container
     * @param signs    the shop signs on the container
     * @return empty when there are no signs, when a sign is not in the registry, when a shop is
     *         not a player's, or when the signs belong to different players: in each case there
     *         is no one player the container's vault could belong to
     */
    public static @NotNull Optional<UUID> playerOwner(@NotNull List<Shop> registry,
                                                     @NotNull Collection<ShopPosition> signs) {
        UUID owner = null;
        for (ShopPosition sign : signs) {
            Optional<Shop> shop = registry.stream().filter(listed -> listed.sign().equals(sign)).findFirst();
            if (shop.isEmpty() || !(shop.get().owner() instanceof ShopOwner.Player player)) {
                return Optional.empty();
            }
            if (owner != null && !owner.equals(player.uuid())) {
                return Optional.empty();
            }
            owner = player.uuid();
        }
        return Optional.ofNullable(owner);
    }
}
