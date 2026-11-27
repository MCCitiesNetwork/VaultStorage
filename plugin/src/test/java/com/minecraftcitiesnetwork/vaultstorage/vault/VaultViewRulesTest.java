package com.minecraftcitiesnetwork.vaultstorage.vault;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VaultViewRulesTest {

    @Test
    void viewingCancelsEveryClickAndDrag() {
        Assertions.assertTrue(VaultViewRules.cancelsClick(VaultAction.VIEW));
        Assertions.assertTrue(VaultViewRules.cancelsDrag(VaultAction.VIEW, false));
        Assertions.assertFalse(VaultViewRules.givesCopy(VaultAction.VIEW, true, true));
    }

    @Test
    void copyingCancelsEveryClickSoNothingCanBeMovedIntoTheVault() {
        // A shift-click or hotbar swap from the player's own inventory would otherwise put a
        // real item into an inventory that is never saved.
        Assertions.assertTrue(VaultViewRules.cancelsClick(VaultAction.COPY));
        Assertions.assertTrue(VaultViewRules.cancelsDrag(VaultAction.COPY, true));
        Assertions.assertFalse(VaultViewRules.cancelsDrag(VaultAction.COPY, false));
    }

    @Test
    void copyingGivesACopyOnlyForAnItemInTheVault() {
        Assertions.assertTrue(VaultViewRules.givesCopy(VaultAction.COPY, true, true));
        Assertions.assertFalse(VaultViewRules.givesCopy(VaultAction.COPY, true, false));
        Assertions.assertFalse(VaultViewRules.givesCopy(VaultAction.COPY, false, true));
    }

    @Test
    void editingCancelsNothingAndIsTheOnlyModeThatSaves() {
        Assertions.assertFalse(VaultViewRules.cancelsClick(VaultAction.EDIT));
        Assertions.assertFalse(VaultViewRules.cancelsDrag(VaultAction.EDIT, true));
        Assertions.assertTrue(VaultViewRules.savesOnClose(VaultAction.EDIT));
        Assertions.assertFalse(VaultViewRules.savesOnClose(VaultAction.COPY));
        Assertions.assertFalse(VaultViewRules.savesOnClose(VaultAction.VIEW));
    }
}
