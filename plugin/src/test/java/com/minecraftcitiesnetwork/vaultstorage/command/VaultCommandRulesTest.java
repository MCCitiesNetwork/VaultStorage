package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.vaultstorage.vault.VaultAction;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VaultCommandRulesTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void theActionWordIsReadInAnyCase() {
        Assertions.assertEquals(Optional.of(VaultAction.EDIT), VaultActionArgument.parse("Edit"));
        Assertions.assertEquals(Optional.of(VaultAction.VIEW), VaultActionArgument.parse("view"));
    }

    @Test
    void theActionWordIsFoldedWithTheRootLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            // In Turkish, "i" upper-cases to a dotted capital that is not the I of VIEW.
            Assertions.assertEquals(Optional.of(VaultAction.VIEW), VaultActionArgument.parse("view"));
            Assertions.assertEquals(List.of("view", "edit", "copy"), VaultActionArgument.words());
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void anUnknownActionWordIsEmpty() {
        Assertions.assertEquals(Optional.empty(), VaultActionArgument.parse("steal"));
        Assertions.assertEquals(Optional.empty(), VaultActionArgument.parse(""));
    }

    @Test
    void anOwnerMayOpenTheirOwnVault() {
        Assertions.assertInstanceOf(OpenOutcome.Allowed.class, OpenOutcome.decide(OWNER, OWNER, false));
    }

    @Test
    void someoneElseMayOpenItOnlyWithTheOthersPermission() {
        Assertions.assertInstanceOf(OpenOutcome.NotYours.class, OpenOutcome.decide(OWNER, OTHER, false));
        Assertions.assertInstanceOf(OpenOutcome.Allowed.class, OpenOutcome.decide(OWNER, OTHER, true));
    }

    @Test
    void aVaultThatDoesNotExistIsReportedEvenToStaff() {
        Assertions.assertInstanceOf(OpenOutcome.NoSuchVault.class, OpenOutcome.decide(null, OTHER, true));
    }
}
