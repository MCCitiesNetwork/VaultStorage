package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import com.minecraftcitiesnetwork.vaultstorage.shop.Shop;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopOwner;
import com.minecraftcitiesnetwork.vaultstorage.shop.ShopPosition;
import io.paradaux.treasury.api.market.ShopResult;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TreasuryShopDirectoryTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static ShopResult shop(boolean admin, String accountType, UUID owner) {
        return new ShopResult("world", null, 10, 65, -3, admin, 7, accountType, null, owner, null,
                "DIAMOND", "Diamond", "Diamond", false, null, new BigDecimal("5.00"), null, 1, 3, 100, true, true);
    }

    @Test
    void aPersonalShopIsItsPlayersAtTheSignsPosition() {
        Assertions.assertEquals(new Shop(new ShopPosition(10, 65, -3), new ShopOwner.Player(ALICE)),
                TreasuryShopDirectory.toShop(shop(false, "PERSONAL", ALICE)));
    }

    @Test
    void aFirmOrGovernmentShopIsAnOrganisations() {
        Assertions.assertEquals(new ShopOwner.Organisation(), TreasuryShopDirectory.toShop(shop(false, "BUSINESS", null)).owner());
        Assertions.assertEquals(new ShopOwner.Organisation(), TreasuryShopDirectory.toShop(shop(false, "GOVERNMENT", null)).owner());
    }

    @Test
    void anAdminShopIsNobodysEvenIfARowNamesAnOwner() {
        Assertions.assertEquals(new ShopOwner.Admin(), TreasuryShopDirectory.toShop(shop(true, "PERSONAL", ALICE)).owner());
    }

    @Test
    void aPersonalShopWithoutAnOwnerIdAndASystemShopAreUnknown() {
        Assertions.assertEquals(new ShopOwner.Unknown(), TreasuryShopDirectory.toShop(shop(false, "PERSONAL", null)).owner());
        Assertions.assertEquals(new ShopOwner.Unknown(), TreasuryShopDirectory.toShop(shop(false, "SYSTEM", null)).owner());
        Assertions.assertEquals(new ShopOwner.Unknown(), TreasuryShopDirectory.toShop(shop(false, null, null)).owner());
    }
}
