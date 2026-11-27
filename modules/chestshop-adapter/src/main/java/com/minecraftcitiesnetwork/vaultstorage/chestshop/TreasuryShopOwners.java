package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerAttachments;
import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerOwnerSource;
import com.minecraftcitiesnetwork.vaultstorage.capture.MarkedContainer;

import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ShopResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Whose a shop's container is, read from Treasury's shop registry. The only class that names a
 * Treasury type, so the module loads on a server without Treasury.
 */
public final class TreasuryShopOwners implements ContainerOwnerSource {

    private static final String PERSONAL = "PERSONAL";
    private static final String BUSINESS = "BUSINESS";
    private static final String GOVERNMENT = "GOVERNMENT";

    private final @NotNull ShopQueryApi shops;
    private final @NotNull ContainerAttachments signs;
    private final @NotNull Executor database;
    private final @NotNull Logger logger;

    private TreasuryShopOwners(@NotNull ShopQueryApi shops, @NotNull ContainerAttachments signs,
                               @NotNull Executor database, @NotNull Logger logger) {
        this.shops = shops;
        this.signs = signs;
        this.database = database;
        this.logger = logger;
    }

    /**
     * @param signs    finds the shop signs on a container
     * @param database where the registry is read: it is a database call
     * @return null when Treasury has not registered its shop registry
     */
    public static @Nullable ContainerOwnerSource create(@NotNull ContainerAttachments signs,
                                                        @NotNull Executor database, @NotNull Logger logger) {
        ShopQueryApi shops = Bukkit.getServicesManager().load(ShopQueryApi.class);
        return shops == null ? null : new TreasuryShopOwners(shops, signs, database, logger);
    }

    /** The player every shop sign on the container belongs to; see {@link ShopOwners#playerOwner}. */
    @Override
    public @NotNull CompletableFuture<Optional<UUID>> ownerOf(@NotNull Block container) {
        List<ShopPosition> onContainer = this.signs.attachedTo(container).stream()
                .map(sign -> new ShopPosition(sign.getX(), sign.getY(), sign.getZ())).toList();
        if (onContainer.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String world = container.getWorld().getName();
        Set<List<Integer>> chunks = new LinkedHashSet<>();
        onContainer.forEach(sign -> chunks.add(List.of(sign.chunkX(), sign.chunkZ())));
        List<CompletableFuture<List<Shop>>> reads = new ArrayList<>();
        chunks.forEach(chunk -> reads.add(shopsInChunk(world, chunk.get(0), chunk.get(1))));
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(done -> {
            List<Shop> registry = new ArrayList<>();
            reads.forEach(read -> registry.addAll(read.join()));
            return ShopOwners.playerOwner(registry, onContainer);
        });
    }

    /** Each player's shop in the chunk, at its sign. A firm's or an admin shop is nobody's. */
    @Override
    public @NotNull CompletableFuture<List<MarkedContainer>> ownedIn(@NotNull String world, int chunkX, int chunkZ) {
        return shopsInChunk(world, chunkX, chunkZ).thenApply(TreasuryShopOwners::playersShops);
    }

    static @NotNull List<MarkedContainer> playersShops(@NotNull List<Shop> registry) {
        List<MarkedContainer> marked = new ArrayList<>();
        for (Shop shop : registry) {
            if (shop.owner() instanceof ShopOwner.Player player) {
                marked.add(new MarkedContainer(shop.sign().x(), shop.sign().y(), shop.sign().z(), player.uuid()));
            }
        }
        return marked;
    }

    /** Reads a database, off the calling thread. A failed read is logged and answers with no shops. */
    private @NotNull CompletableFuture<List<Shop>> shopsInChunk(@NotNull String world, int chunkX, int chunkZ) {
        return CompletableFuture
                .supplyAsync(() -> this.shops.shopsInChunk(world, chunkX, chunkZ).stream()
                        .map(TreasuryShopOwners::toShop).toList(), this.database)
                .exceptionally(failure -> {
                    this.logger.log(Level.WARNING, "Could not read the shops in chunk " + chunkX + "," + chunkZ
                            + " of " + world + " from Treasury; treating it as having none.", failure);
                    return List.of();
                });
    }

    static @NotNull Shop toShop(@NotNull ShopResult shop) {
        return new Shop(new ShopPosition(shop.signX(), shop.signY(), shop.signZ()), ownerKind(shop));
    }

    /** Treasury records an owner's id only for a personal shop. */
    private static @NotNull ShopOwner ownerKind(@NotNull ShopResult shop) {
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
