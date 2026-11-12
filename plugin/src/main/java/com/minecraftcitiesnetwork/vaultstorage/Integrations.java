package com.minecraftcitiesnetwork.vaultstorage;

import com.minecraftcitiesnetwork.vaultstorage.chestshop.ChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.chestshop.NoOpChestShopService;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import java.util.UUID;
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

    public void chestShop(@Nullable ChestShopService service) {
        this.chestShop = service;
    }

    public @NotNull ChestShopService chestShop() {
        ChestShopService service = this.chestShop;
        return service == null ? NO_CHEST_SHOP : service;
    }
}
