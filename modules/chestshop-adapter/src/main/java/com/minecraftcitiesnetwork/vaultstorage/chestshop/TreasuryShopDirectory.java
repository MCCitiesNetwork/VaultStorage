package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import com.minecraftcitiesnetwork.vaultstorage.shop.Shop;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopDirectory;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopOwner;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopPosition;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ShopResult;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ShopDirectory} read from Treasury's shop registry. The only class that names a Treasury
 * type, so the module loads on a server without Treasury.
 */
public final class TreasuryShopDirectory implements ShopDirectory {

    private static final String PERSONAL = "PERSONAL";
    private static final String BUSINESS = "BUSINESS";
    private static final String GOVERNMENT = "GOVERNMENT";

    private final @NotNull ShopQueryApi shops;
    private final @NotNull Executor database;
    private final @NotNull Logger logger;

    private TreasuryShopDirectory(@NotNull ShopQueryApi shops, @NotNull Executor database, @NotNull Logger logger) {
        this.shops = shops;
        this.database = database;
        this.logger = logger;
    }

    /**
     * @param database where the registry is read: it is a database call
     * @return null when Treasury has not registered its shop registry
     */
    public static @Nullable ShopDirectory create(@NotNull Executor database, @NotNull Logger logger) {
        ShopQueryApi shops = Bukkit.getServicesManager().load(ShopQueryApi.class);
        return shops == null ? null : new TreasuryShopDirectory(shops, database, logger);
    }

    @Override
    public @NotNull CompletableFuture<List<Shop>> shopsInChunk(@NotNull String world, int chunkX, int chunkZ) {
        return CompletableFuture
                .supplyAsync(() -> this.shops.shopsInChunk(world, chunkX, chunkZ).stream()
                        .map(TreasuryShopDirectory::toShop).toList(), this.database)
                .exceptionally(failure -> {
                    this.logger.log(Level.WARNING, "Could not read the shops in chunk " + chunkX + "," + chunkZ
                            + " of " + world + " from Treasury; treating it as having none.", failure);
                    return List.of();
                });
    }

    static @NotNull Shop toShop(@NotNull ShopResult shop) {
        return new Shop(new ShopPosition(shop.signX(), shop.signY(), shop.signZ()), ownerOf(shop));
    }

    /** Treasury records an owner's id only for a personal shop. */
    private static @NotNull ShopOwner ownerOf(@NotNull ShopResult shop) {
        if (shop.adminShop()) {
            return new ShopOwner.Admin();
        }
        if (PERSONAL.equals(shop.shopAccountType()) && shop.shopOwnerUuid() != null) {
            return new ShopOwner.Player(shop.shopOwnerUuid());
        }
        if (BUSINESS.equals(shop.shopAccountType()) || GOVERNMENT.equals(shop.shopAccountType())) {
            return new ShopOwner.Organisation();
        }
        return new ShopOwner.Unknown();
    }
}
