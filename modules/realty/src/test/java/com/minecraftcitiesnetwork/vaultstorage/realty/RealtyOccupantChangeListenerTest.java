package com.minecraftcitiesnetwork.vaultstorage.realty;

import io.github.md5sha256.realty.api.AccountKind;
import io.github.md5sha256.realty.api.Party;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RealtyOccupantChangeListenerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void aPlayerWhoBecomesTheOccupantIsSweptFor() {
        Assertions.assertEquals(Optional.of(PLAYER), RealtyOccupantChangeListener.sweptFor(Party.personal(PLAYER)));
    }

    @Test
    void aClearedTenancyStartsNoSweep() {
        Assertions.assertEquals(Optional.empty(), RealtyOccupantChangeListener.sweptFor(null));
    }

    @Test
    void anAccountOrAGroupAsOccupantStartsNoSweep() {
        Party account = Party.account(7, AccountKind.values()[0]);
        Party group = Party.group("staff", 7, AccountKind.values()[0]);

        Assertions.assertEquals(Optional.empty(), RealtyOccupantChangeListener.sweptFor(account));
        Assertions.assertEquals(Optional.empty(), RealtyOccupantChangeListener.sweptFor(group));
    }
}
