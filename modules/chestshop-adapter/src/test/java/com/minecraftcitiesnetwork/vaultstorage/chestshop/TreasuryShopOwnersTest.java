package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import com.minecraftcitiesnetwork.vaultstorage.capture.MarkedContainer;
import java.util.List;
import io.paradaux.treasury.api.market.ShopResult;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TreasuryShopOwnersTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private static ShopResult shop(boolean admin, String accountType, UUID owner) {
        return new ShopResult("world", null, 10, 65, -3, admin, 7, accountType, null, owner, null,
                "DIAMOND", "Diamond", "Diamond", false, null, new BigDecimal("5.00"), null, 1, 3, 100, true, true);
    }

    @Test
    void aPersonalShopIsItsPlayersAtTheSignsPosition() {
        Assertions.assertEquals(new Shop(new ShopPosition(10, 65, -3), new ShopOwner.Player(ALICE)),
                TreasuryShopOwners.toShop(shop(false, "PERSONAL", ALICE)));
    }

    @Test
    void aFirmOrGovernmentShopIsAnOrganisations() {
        Assertions.assertEquals(new ShopOwner.Organisation(), TreasuryShopOwners.toShop(shop(false, "BUSINESS", null)).owner());
        Assertions.assertEquals(new ShopOwner.Organisation(), TreasuryShopOwners.toShop(shop(false, "GOVERNMENT", null)).owner());
    }

    @Test
    void anAdminShopIsNobodysEvenIfARowNamesAnOwner() {
        Assertions.assertEquals(new ShopOwner.Admin(), TreasuryShopOwners.toShop(shop(true, "PERSONAL", ALICE)).owner());
    }

    @Test
    void aPersonalShopWithoutAnOwnerIdAndASystemShopAreUnknown() {
        Assertions.assertEquals(new ShopOwner.Unknown(), TreasuryShopOwners.toShop(shop(false, "PERSONAL", null)).owner());
        Assertions.assertEquals(new ShopOwner.Unknown(), TreasuryShopOwners.toShop(shop(false, "SYSTEM", null)).owner());
        Assertions.assertEquals(new ShopOwner.Unknown(), TreasuryShopOwners.toShop(shop(false, null, null)).owner());
    }

    @Test
    void onlyAPlayersShopMarksAContainerAsSomebodys() {
        List<Shop> registry = List.of(
                TreasuryShopOwners.toShop(shop(false, "PERSONAL", ALICE)),
                TreasuryShopOwners.toShop(shop(false, "BUSINESS", null)),
                TreasuryShopOwners.toShop(shop(true, "PERSONAL", ALICE)));

        Assertions.assertEquals(List.of(new MarkedContainer(10, 65, -3, ALICE)),
                TreasuryShopOwners.playersShops(registry));
    }
}
