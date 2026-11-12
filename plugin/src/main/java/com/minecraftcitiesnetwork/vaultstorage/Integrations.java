package com.minecraftcitiesnetwork.vaultstorage;

import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.NoOpChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import com.minecraftcitiesnetwork.vaultstorage.placement.PlacementGrant;
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
    private final List<PlacementGrant> placementGrants = new CopyOnWriteArrayList<>();

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
            } catch (RuntimeException ignored) {
                // Treated as "this grant does not apply".
            }
        }
        return false;
    }

    public void chestShop(@Nullable ChestShopService service) {
        this.chestShop = service;
    }

    public @NotNull ChestShopService chestShop() {
        ChestShopService service = this.chestShop;
        return service == null ? NO_CHEST_SHOP : service;
    }
}
