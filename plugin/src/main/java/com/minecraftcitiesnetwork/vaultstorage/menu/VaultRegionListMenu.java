package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists regions a page at a time: those in the player's world whose name contains what was
 * searched for, or a given few. Picking one scans it.
 */
public final class VaultRegionListMenu extends ChildMenuImp {

    private static final int PAGE_SIZE = 6;

    private final @NotNull VaultUIContext uiContext;
    private final @NotNull String query;
    /** When not null, exactly these regions are listed and nothing is searched. Sorted. */
    private final @Nullable List<String> given;
    private final int page;

    private VaultRegionListMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx,
                                @NotNull String query, @Nullable List<String> given, int page) {
        super(player, parent);
        this.uiContext = ctx;
        this.query = query;
        this.given = given;
        this.page = page;
    }

    /** The regions of the player's world whose name contains the query. */
    public static @NotNull VaultRegionListMenu search(@NotNull Player player, @NotNull ParentMenu parent,
                                                      @NotNull VaultUIContext ctx, @NotNull String query) {
        return new VaultRegionListMenu(player, parent, ctx, query.trim(), null, 0);
    }

    /** Exactly these regions, such as the ones that overlap where the player stands. */
    public static @NotNull VaultRegionListMenu of(@NotNull Player player, @NotNull ParentMenu parent,
                                                  @NotNull VaultUIContext ctx, @NotNull List<String> regionIds) {
        return new VaultRegionListMenu(player, parent, ctx, "",
                regionIds.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), 0);
    }

    /** The region ids to list, sorted, so a page shows the same regions each time it is built. */
    private @NotNull List<String> regionIds() {
        if (this.given != null) {
            return this.given;
        }
        // Without the staff view a player is shown only the regions they have a part in.
        return plugin.worldGuard().regionIds(player.getWorld(), this.query,
                player.hasPermission(Permissions.ADMIN) ? null : player.getUniqueId());
    }

    @Override
    protected @NotNull AutoDialog build() {
        List<String> ids = regionIds();
        Paging paging = Paging.of(ids.size(), PAGE_SIZE, this.page);
        AutoDialog.Builder builder = AutoDialog.builder()
                .title(plugin.text().of(MessageKeys.REGIONS_TITLE,
                        Map.of("%player%", player.getName(), "%query%", this.query)))
                .line(plugin.text().of(MessageKeys.REGIONS_HEADER, Map.of(
                        "%count%", String.valueOf(paging.total()),
                        "%page%", String.valueOf(paging.page() + 1),
                        "%pages%", String.valueOf(paging.pages()),
                        "%query%", this.query)));
        if (ids.isEmpty()) {
            builder.line(plugin.text().of(MessageKeys.REGIONS_NONE_FOUND));
        }
        for (String id : ids.subList(paging.offset(), Math.min(ids.size(), paging.offset() + PAGE_SIZE))) {
            builder.button(plugin.text().of(MessageKeys.REGIONS_REGION_BTN, Map.of("%region%", id)),
                    actor -> RegionScans.open(parentMenu(), actor, id, uiContext));
        }
        if (paging.hasPrevious()) {
            builder.button(plugin.text().of(MessageKeys.REGIONS_PREV_PAGE_BTN), actor -> at(actor, paging.page() - 1).open());
        }
        if (paging.hasNext()) {
            builder.button(plugin.text().of(MessageKeys.REGIONS_NEXT_PAGE_BTN), actor -> at(actor, paging.page() + 1).open());
        }
        builder.button(plugin.text().of(MessageKeys.REGIONS_BACK_BTN),
                actor -> new VaultScanMenu(actor, parentMenu(), uiContext).open());
        return builder.build();
    }

    private @NotNull VaultRegionListMenu at(@NotNull Player actor, int otherPage) {
        return new VaultRegionListMenu(actor, parentMenu(), this.uiContext, this.query, this.given, otherPage);
    }
}
