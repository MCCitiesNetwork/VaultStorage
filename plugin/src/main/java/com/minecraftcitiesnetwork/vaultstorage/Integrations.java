package com.minecraftcitiesnetwork.vaultstorage;

import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopDirectory;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopSigns;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import com.minecraftcitiesnetwork.vaultstorage.placement.PlacementGrant;
import com.minecraftcitiesnetwork.vaultstorage.vault.OwnerNotifier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Location;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What feature modules supply to the host. Each slot has a built-in default; a module fills its
 * slot when it starts and passes {@code null} to restore the default when it stops.
 *
 * <p>Host code reads a slot each time it uses it, never once at construction. The fields are
 * volatile because name lookups complete on other threads.
 */
public final class Integrations {

    private volatile @Nullable NameSource primaryNames;
    private volatile @Nullable NameSource bedrockNames;
    private volatile @Nullable Predicate<UUID> bedrockClient;
    private volatile @Nullable ShopDirectory shopDirectory;
    private volatile @Nullable OwnerNotifier ownerNotifier;
    private final List<PlacementGrant> placementGrants = new CopyOnWriteArrayList<>();
    private final List<ShopSigns> shopSigns = new CopyOnWriteArrayList<>();
    private final ShopSigns everyShopSign = new EveryShopSign();
    private final @NotNull Logger logger;

    public Integrations(@NotNull Logger logger) {
        this.logger = logger;
    }

    public void primaryNames(@Nullable NameSource source) {
        this.primaryNames = source;
    }

    public @Nullable NameSource primaryNames() {
        return this.primaryNames;
    }

    public void bedrockNames(@Nullable NameSource source) {
        this.bedrockNames = source;
    }

    public @Nullable NameSource bedrockNames() {
        return this.bedrockNames;
    }

    public void bedrockClient(@Nullable Predicate<UUID> check) {
        this.bedrockClient = check;
    }

    /** Whether the player is on a Bedrock client. False when no module can tell. */
    public boolean isBedrockClient(@NotNull UUID player) {
        Predicate<UUID> check = this.bedrockClient;
        return check != null && check.test(player);
    }

    public void ownerNotifier(@Nullable OwnerNotifier notifier) {
        this.ownerNotifier = notifier;
    }

    /** How a vault's owner is told about it; null when no notification module is loaded. */
    public @Nullable OwnerNotifier ownerNotifier() {
        return this.ownerNotifier;
    }

    public void addPlacementGrant(@NotNull PlacementGrant grant) {
        this.placementGrants.add(grant);
    }

    public void removePlacementGrant(@NotNull PlacementGrant grant) {
        this.placementGrants.remove(grant);
    }

    /**
     * Whether any grant lets the player place a vault at the target. A grant that fails is a
     * no: a broken integration must not open placement to everyone.
     */
    public boolean mayPlace(@NotNull UUID player, @NotNull Location target) {
        for (PlacementGrant grant : this.placementGrants) {
            try {
                if (grant.mayPlace(player, target)) {
                    return true;
                }
            } catch (RuntimeException ex) {
                // Counts as "this grant does not apply": a broken module must not open land up.
                this.logger.log(Level.WARNING, "A placement grant failed and was counted as a refusal: "
                        + grant.getClass().getName(), ex);
            }
        }
        return false;
    }

    public void shopDirectory(@Nullable ShopDirectory directory) {
        this.shopDirectory = directory;
    }

    /** The shop plugin's registry of shops and their owners; null when no module supplies one. */
    public @Nullable ShopDirectory shopDirectory() {
        return this.shopDirectory;
    }

    public void addShopSigns(@NotNull ShopSigns signs) {
        this.shopSigns.add(signs);
    }

    public void removeShopSigns(@NotNull ShopSigns signs) {
        this.shopSigns.remove(signs);
    }

    /** The shop signs of every module that knows a shop plugin. With none, no block is a shop sign. */
    public @NotNull ShopSigns shopSigns() {
        return this.everyShopSign;
    }

    /** Asks each module in turn. One that fails is logged and counts as knowing no sign. */
    private final class EveryShopSign implements ShopSigns {

        @Override
        public boolean isShopSign(@Nullable Block block) {
            for (ShopSigns signs : Integrations.this.shopSigns) {
                try {
                    if (signs.isShopSign(block)) {
                        return true;
                    }
                } catch (RuntimeException ex) {
                    failed(signs, ex);
                }
            }
            return false;
        }

        @Override
        public @NotNull List<Block> findShopSigns(@Nullable Block containerBlock) {
            List<Block> found = new ArrayList<>();
            for (ShopSigns signs : Integrations.this.shopSigns) {
                try {
                    found.addAll(signs.findShopSigns(containerBlock));
                } catch (RuntimeException ex) {
                    failed(signs, ex);
                }
            }
            return found;
        }

        @Override
        public boolean removeShopSign(@Nullable Player actor, @Nullable Block signBlock) {
            for (ShopSigns signs : Integrations.this.shopSigns) {
                try {
                    if (signs.isShopSign(signBlock)) {
                        return signs.removeShopSign(actor, signBlock);
                    }
                } catch (RuntimeException ex) {
                    failed(signs, ex);
                }
            }
            return false;
        }

        private void failed(@NotNull ShopSigns signs, @NotNull RuntimeException ex) {
            Integrations.this.logger.log(Level.WARNING, "A shop sign lookup failed and was counted as no sign: "
                    + signs.getClass().getName(), ex);
        }
    }
}
