package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Who a shop belongs to, as the shop plugin's own registry records it. */
public sealed interface ShopOwner {

    /** A player's own shop. The only kind whose container can become somebody's vault. */
    record Player(@NotNull UUID uuid) implements ShopOwner {
    }

    /** A firm's or a government account's shop. */
    record Organisation() implements ShopOwner {
    }

    /** A shop with no owner and no stock. */
    record Admin() implements ShopOwner {
    }

    /** The registry lists the shop without saying whose it is. */
    record Unknown() implements ShopOwner {
    }
}
