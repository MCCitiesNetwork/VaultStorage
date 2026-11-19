package com.minecraftcitiesnetwork.vaultstorage;

import java.util.logging.Logger;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ShopDirectory;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.NoOpChestShopService;
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

    private static final ChestShopService NO_CHEST_SHOP = new NoOpChestShopService();

    private volatile @Nullable NameSource primaryNames;
    private volatile @Nullable NameSource bedrockNames;
    private volatile @Nullable Predicate<UUID> bedrockClient;
    private volatile @Nullable ChestShopService chestShop;
    private volatile @Nullable ShopDirectory shopDirectory;
    private volatile @Nullable OwnerNotifier ownerNotifier;
    private final List<PlacementGrant> placementGrants = new CopyOnWriteArrayList<>();
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

    public void chestShop(@Nullable ChestShopService service) {
        this.chestShop = service;
    }

    public @NotNull ChestShopService chestShop() {
        ChestShopService service = this.chestShop;
        return service == null ? NO_CHEST_SHOP : service;
    }
}
