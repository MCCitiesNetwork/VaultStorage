package com.minecraftcitiesnetwork.vaultstorage;

import com.minecraftcitiesnetwork.vaultstorage.shop.ShopSigns;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** The shop signs the host sees: what the modules that know a shop plugin tell it, taken together. */
class ShopSignsTest {

    /** A block that is only ever compared by identity. */
    private static Block block() {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A shop plugin with one sign on one container. Records the signs it was asked to remove. */
    private static final class OneShop implements ShopSigns {
        final Block sign = block();
        final Block container = block();
        final List<Block> removed = new ArrayList<>();

        @Override
        public boolean isShopSign(@Nullable Block block) {
            return block == this.sign;
        }

        @Override
        public @NotNull List<Block> findShopSigns(@Nullable Block containerBlock) {
            return containerBlock == this.container ? List.of(this.sign) : List.of();
        }

        @Override
        public boolean removeShopSign(@Nullable Player actor, @Nullable Block signBlock) {
            this.removed.add(signBlock);
            return true;
        }
    }

    /** A module whose shop plugin is broken. */
    private static final class Broken implements ShopSigns {
        @Override
        public boolean isShopSign(@Nullable Block block) {
            throw new IllegalStateException("broken");
        }

        @Override
        public @NotNull List<Block> findShopSigns(@Nullable Block containerBlock) {
            throw new IllegalStateException("broken");
        }

        @Override
        public boolean removeShopSign(@Nullable Player actor, @Nullable Block signBlock) {
            throw new IllegalStateException("broken");
        }
    }

    private final Integrations integrations = new Integrations(Logger.getAnonymousLogger());

    @Test
    void withNoShopModuleNoBlockIsAShopSign() {
        Block anything = block();

        Assertions.assertFalse(this.integrations.shopSigns().isShopSign(anything));
        Assertions.assertEquals(List.of(), this.integrations.shopSigns().findShopSigns(anything));
        Assertions.assertFalse(this.integrations.shopSigns().removeShopSign(null, anything));
    }

    @Test
    void aModulesSignsAreSeenWhileItIsLoaded() {
        OneShop shop = new OneShop();
        this.integrations.addShopSigns(shop);

        Assertions.assertTrue(this.integrations.shopSigns().isShopSign(shop.sign));
        Assertions.assertEquals(List.of(shop.sign), this.integrations.shopSigns().findShopSigns(shop.container));

        this.integrations.removeShopSigns(shop);

        Assertions.assertFalse(this.integrations.shopSigns().isShopSign(shop.sign));
        Assertions.assertEquals(List.of(), this.integrations.shopSigns().findShopSigns(shop.container));
    }

    @Test
    void aSignIsRemovedByTheModuleThatKnowsIt() {
        OneShop first = new OneShop();
        OneShop second = new OneShop();
        this.integrations.addShopSigns(first);
        this.integrations.addShopSigns(second);

        Assertions.assertTrue(this.integrations.shopSigns().removeShopSign(null, second.sign));

        Assertions.assertEquals(List.of(), first.removed);
        Assertions.assertEquals(List.of(second.sign), second.removed);
    }

    @Test
    void aBlockNoModuleKnowsIsNotRemoved() {
        OneShop shop = new OneShop();
        this.integrations.addShopSigns(shop);

        Assertions.assertFalse(this.integrations.shopSigns().removeShopSign(null, block()));
        Assertions.assertEquals(List.of(), shop.removed);
    }

    @Test
    void aModuleThatFailsCountsAsKnowingNoSignAndTheOthersAreStillAsked() {
        OneShop shop = new OneShop();
        this.integrations.addShopSigns(new Broken());
        this.integrations.addShopSigns(shop);

        Assertions.assertTrue(this.integrations.shopSigns().isShopSign(shop.sign));
        Assertions.assertEquals(List.of(shop.sign), this.integrations.shopSigns().findShopSigns(shop.container));
        Assertions.assertTrue(this.integrations.shopSigns().removeShopSign(null, shop.sign));
        Assertions.assertFalse(this.integrations.shopSigns().isShopSign(block()));
    }
}
