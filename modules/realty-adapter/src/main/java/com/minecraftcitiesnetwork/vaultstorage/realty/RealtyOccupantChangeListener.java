package com.minecraftcitiesnetwork.vaultstorage.realty;

import io.github.md5sha256.realty.api.Party;
import io.github.md5sha256.realty.api.event.AuctionWonPurchaseEvent;
import io.github.md5sha256.realty.api.event.OfferPurchaseCompletedEvent;
import io.github.md5sha256.realty.api.event.RealtyRegionEvent;
import io.github.md5sha256.realty.api.event.RegionBoughtEvent;
import io.github.md5sha256.realty.api.event.RegionRentedEvent;
import io.github.md5sha256.realty.api.event.TenantSetEvent;
import io.github.md5sha256.realty.api.event.TitleTransferredEvent;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Starts a sweep when Realty gives a region a new occupant. Names Realty's types, so it is
 * only built when Realty is present.
 *
 * <p>Realty fires these after it has replaced the region's WorldGuard owners and members with
 * the new occupant alone.
 */
public final class RealtyOccupantChangeListener implements Listener {

    private final AutoVaultService autoVaultService;

    public RealtyOccupantChangeListener(@NotNull AutoVaultService autoVaultService) {
        this.autoVaultService = autoVaultService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTitleTransferred(@NotNull TitleTransferredEvent event) {
        submit(event, event.getNewTitleHolder());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegionBought(@NotNull RegionBoughtEvent event) {
        submit(event, event.getNewTitleHolder());
    }

    /** A sale that ended by auction. Realty fires this in place of {@link RegionBoughtEvent}. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuctionWon(@NotNull AuctionWonPurchaseEvent event) {
        submit(event, event.getNewTitleHolder());
    }

    /** A sale that ended by an accepted offer, likewise. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOfferCompleted(@NotNull OfferPurchaseCompletedEvent event) {
        submit(event, event.getNewTitleHolder());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegionRented(@NotNull RegionRentedEvent event) {
        submit(event, event.getTenant());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTenantSet(@NotNull TenantSetEvent event) {
        submit(event, event.getNewTenant());
    }

    private void submit(@NotNull RealtyRegionEvent event, @Nullable Party newOccupant) {
        sweptFor(newOccupant).ifPresent(player ->
                this.autoVaultService.submit(event.getWorldId(), event.getRegionId(), player));
    }

    /**
     * The player a sweep is run for. Empty when the tenancy or title was cleared, and when
     * the new occupant is an account or a group: a sweep needs one player to keep the
     * containers of and to tell.
     */
    static @NotNull Optional<UUID> sweptFor(@Nullable Party newOccupant) {
        return Party.playerUuidOf(newOccupant);
    }
}
