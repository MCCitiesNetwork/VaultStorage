package com.minecraftcitiesnetwork.vaultstorage.realty;

import io.github.md5sha256.realty.api.AccountKind;
import io.github.md5sha256.realty.api.Party;
import io.github.md5sha256.realty.api.event.AuctionWonPurchaseEvent;
import io.github.md5sha256.realty.api.event.OfferPurchaseCompletedEvent;
import io.github.md5sha256.realty.api.event.RegionBoughtEvent;
import io.github.md5sha256.realty.api.event.RegionRentedEvent;
import io.github.md5sha256.realty.api.event.TenantSetEvent;
import io.github.md5sha256.realty.api.event.TitleTransferredEvent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.event.EventHandler;
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

    /** Realty has one event per way a region gets a new occupant; a sale alone has three. */
    @Test
    void everyWayARegionChangesHandsIsListenedFor() {
        Set<Class<?>> listenedFor = Arrays.stream(RealtyOccupantChangeListener.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(EventHandler.class))
                .map(method -> method.getParameterTypes()[0])
                .collect(Collectors.toSet());

        Assertions.assertEquals(Set.of(RegionBoughtEvent.class, AuctionWonPurchaseEvent.class,
                OfferPurchaseCompletedEvent.class, TitleTransferredEvent.class, RegionRentedEvent.class,
                TenantSetEvent.class), listenedFor);
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
