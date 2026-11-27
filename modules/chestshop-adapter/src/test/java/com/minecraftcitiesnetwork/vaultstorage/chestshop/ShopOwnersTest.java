package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ShopOwnersTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final ShopPosition SIGN = new ShopPosition(10, 65, -3);
    private static final ShopPosition OTHER_SIGN = new ShopPosition(11, 64, -3);

    @Test
    void aPersonalShopBelongsToItsPlayer() {
        List<Shop> registry = List.of(new Shop(SIGN, new ShopOwner.Player(ALICE)));
        Assertions.assertEquals(Optional.of(ALICE), ShopOwners.playerOwner(registry, List.of(SIGN)));
    }

    @Test
    void aSignTheRegistryDoesNotKnowHasNoOwner() {
        List<Shop> registry = List.of(new Shop(OTHER_SIGN, new ShopOwner.Player(ALICE)));
        Assertions.assertEquals(Optional.empty(), ShopOwners.playerOwner(registry, List.of(SIGN)));
    }

    @Test
    void aFirmAnAdminAndAnUnknownShopHaveNoPlayerToOwnAVault() {
        for (ShopOwner owner : List.of(new ShopOwner.Organisation(), new ShopOwner.Admin(), new ShopOwner.Unknown())) {
            Assertions.assertEquals(Optional.empty(),
                    ShopOwners.playerOwner(List.of(new Shop(SIGN, owner)), List.of(SIGN)));
        }
    }

    @Test
    void twoSignsOfOnePlayerOnAContainerGiveThatPlayer() {
        List<Shop> registry = List.of(new Shop(SIGN, new ShopOwner.Player(ALICE)),
                new Shop(OTHER_SIGN, new ShopOwner.Player(ALICE)));
        Assertions.assertEquals(Optional.of(ALICE), ShopOwners.playerOwner(registry, List.of(SIGN, OTHER_SIGN)));
    }

    @Test
    void signsOfDifferentOwnersOnOneContainerGiveNobody() {
        // Whose vault would it be? Nobody's: it is left for staff.
        List<Shop> players = List.of(new Shop(SIGN, new ShopOwner.Player(ALICE)),
                new Shop(OTHER_SIGN, new ShopOwner.Player(BOB)));
        Assertions.assertEquals(Optional.empty(), ShopOwners.playerOwner(players, List.of(SIGN, OTHER_SIGN)));

        List<Shop> mixed = List.of(new Shop(SIGN, new ShopOwner.Player(ALICE)),
                new Shop(OTHER_SIGN, new ShopOwner.Organisation()));
        Assertions.assertEquals(Optional.empty(), ShopOwners.playerOwner(mixed, List.of(SIGN, OTHER_SIGN)));
    }

    @Test
    void oneSignMissingFromTheRegistryGivesNobody() {
        List<Shop> registry = List.of(new Shop(SIGN, new ShopOwner.Player(ALICE)));
        Assertions.assertEquals(Optional.empty(), ShopOwners.playerOwner(registry, List.of(SIGN, OTHER_SIGN)));
    }

    @Test
    void aContainerWithNoSignsHasNoShopOwner() {
        Assertions.assertEquals(Optional.empty(),
                ShopOwners.playerOwner(List.of(new Shop(SIGN, new ShopOwner.Player(ALICE))), List.of()));
    }
}
