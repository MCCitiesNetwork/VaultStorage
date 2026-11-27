package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import java.time.Instant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VaultLabelTest {

    @Test
    void aMaterialIsShownAsWords() {
        Assertions.assertEquals("Chest", VaultLabel.materialName("CHEST"));
        Assertions.assertEquals("Trapped Chest", VaultLabel.materialName("TRAPPED_CHEST"));
        Assertions.assertEquals("Light Blue Shulker Box", VaultLabel.materialName("LIGHT_BLUE_SHULKER_BOX"));
    }

    @Test
    void theDateIsTheDayInUtc() {
        // 23:30 UTC is already the next day east of Greenwich; the label must not depend on the server's zone.
        Assertions.assertEquals("2026-11-12", VaultLabel.date(Instant.parse("2026-11-12T23:30:00Z")));
        Assertions.assertEquals("2026-11-13", VaultLabel.date(Instant.parse("2026-11-13T00:00:00Z")));
    }

    @Test
    void theItemCountPicksItsOwnSentence() {
        Assertions.assertEquals(MessageKeys.LIST_ITEMS_NONE, VaultLabel.itemsKey(0));
        Assertions.assertEquals(MessageKeys.LIST_ITEMS_ONE, VaultLabel.itemsKey(1));
        Assertions.assertEquals(MessageKeys.LIST_ITEMS_MANY, VaultLabel.itemsKey(2));
    }
}
