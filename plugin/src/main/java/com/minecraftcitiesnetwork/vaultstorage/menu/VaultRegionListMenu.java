package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.menu.AutoDialog;
import com.minecraftcitiesnetwork.vaultstorage.menu.ParentMenu;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Child menu that lists regions in the current world with pagination and a text filter.
 * Selecting a region opens VaultScanMenu in results mode.
 */
public class VaultRegionListMenu extends ChildMenuImp {


    /**
     * YAML header describing configuration placeholders.
     */


    private static final int PAGE_SIZE = 6;

    private final String query; // used only in browse mode
    private final int page; // 0-based
    private final List<String> subsetIds; // when non-null, list only these region ids

    /** Browse mode: filter by query across all regions in world. */
    public VaultRegionListMenu(
            @NotNull Player player,
            @NotNull ParentMenu parent,
            @NotNull String query,
            int page
    ) {
        super(player, parent, "vault_region_list");
        this.query = query.trim();
        this.page = Math.max(0, page);
        this.subsetIds = null;
        setDialog(build());
    }

    /** Subset mode: list exactly these region ids (e.g., regions at player's location). */
    public VaultRegionListMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull List<String> regionIds, int page) {
        super(player, parent, "vault_region_list_subset");
        this.query = "";
        this.page = Math.max(0, page);
        // normalize: copy and sort case-insensitively
        List<String> ids = new ArrayList<>(regionIds);
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        this.subsetIds = ids;
        setDialog(build());
    }

    private Dialog build() {
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> phTitle = Map.of("%player%", getPlayer().getName(), "%query%", query == null ? "" : query);
        builder.title(plugin.text().of(MessageKeys.REGIONS_TITLE, phTitle));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        if (subsetIds != null) {
            // Subset mode: paginate over provided ids only
            int total = subsetIds.size();
            int pages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
            int currentPage = Math.min(Math.max(0, page), pages - 1);
            int from = currentPage * PAGE_SIZE;
            int to = Math.min(total, from + PAGE_SIZE);

            Map<String,String> phHeader = Map.of(
                    "%count%", String.valueOf(total),
                    "%page%", String.valueOf(currentPage + 1),
                    "%pages%", String.valueOf(pages),
                    "%query%", ""
            );
            builder.addBody(DialogBody.plainMessage(plugin.text().of(MessageKeys.REGIONS_HEADER, phHeader)));

            if (from < to) {
                for (int i = from; i < to; i++) {
                    String id = subsetIds.get(i);
                    Map<String,String> ph = Map.of("%region%", id);
                    builder.button(plugin.text().of(MessageKeys.REGIONS_REGION_BTN, ph), ctx -> openResultsFor(ctx.player(), id));
                }
            } else {
                builder.addBody(DialogBody.plainMessage(plugin.text().of(MessageKeys.REGIONS_NONE_FOUND)));
            }

            if (pages > 1) {
                if (currentPage > 0) builder.button(plugin.text().of(MessageKeys.REGIONS_PREV_PAGE_BTN), ctx -> new VaultRegionListMenu(ctx.player(), getParentMenu(), subsetIds, currentPage - 1).open());
                if (currentPage < pages - 1) builder.button(plugin.text().of(MessageKeys.REGIONS_NEXT_PAGE_BTN), ctx -> new VaultRegionListMenu(ctx.player(), getParentMenu(), subsetIds, currentPage + 1).open());
            }

            builder.button(plugin.text().of(MessageKeys.REGIONS_BACK_BTN), ctx -> {
                VaultUIContext backCtx = ctx.player().hasPermission(Permissions.ADMIN) ? VaultUIContext.admin(ctx.player().getUniqueId()) : VaultUIContext.self(ctx.player().getUniqueId());
                new VaultScanMenu(ctx.player(), getParentMenu(), backCtx).open();
            });
            return builder.build();
        }

        // Browse mode
        WorldGuardService wgs = plugin.worldGuard();
        if (wgs == null) {
            builder.addBody(DialogBody.plainMessage(plugin.text().of(MessageKeys.REGIONS_SERVICES_MISSING)));
            builder.button(plugin.text().of(MessageKeys.REGIONS_BACK_BTN), ctx -> new VaultScanMenu(ctx.player(), getParentMenu()).open());
            return builder.build();
        }

        World world = getPlayer().getWorld();
        List<VaultRegion> all = wgs.getRegionsIn(world);
        // Without the staff view a player is shown only the regions they have a part in.
        List<VaultRegion> filtered = filterAndSort(all, query).stream()
                .filter(region -> mayScan(getPlayer(), region)).toList();

        int total = filtered.size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) PAGE_SIZE));
        int currentPage = Math.min(Math.max(0, page), pages - 1);
        int from = currentPage * PAGE_SIZE;
        int to = Math.min(total, from + PAGE_SIZE);

        Map<String,String> phHeader = Map.of(
                "%count%", String.valueOf(total),
                "%page%", String.valueOf(currentPage + 1),
                "%pages%", String.valueOf(pages),
                "%query%", query
        );
        builder.addBody(DialogBody.plainMessage(plugin.text().of(MessageKeys.REGIONS_HEADER, phHeader)));

        if (from < to) {
            for (int i = from; i < to; i++) {
                VaultRegion r = filtered.get(i);
                Map<String,String> ph = Map.of("%region%", r.id());
                builder.button(plugin.text().of(MessageKeys.REGIONS_REGION_BTN, ph), ctx -> openResultsFor(ctx.player(), r.id()));
            }
        } else {
            builder.addBody(DialogBody.plainMessage(plugin.text().of(MessageKeys.REGIONS_NONE_FOUND)));
        }

        if (pages > 1) {
            if (currentPage > 0) builder.button(plugin.text().of(MessageKeys.REGIONS_PREV_PAGE_BTN), ctx -> new VaultRegionListMenu(ctx.player(), getParentMenu(), query, currentPage - 1).open());
            if (currentPage < pages - 1) builder.button(plugin.text().of(MessageKeys.REGIONS_NEXT_PAGE_BTN), ctx -> new VaultRegionListMenu(ctx.player(), getParentMenu(), query, currentPage + 1).open());
        }

        builder.button(plugin.text().of(MessageKeys.REGIONS_BACK_BTN), ctx -> {
            VaultUIContext backCtx = ctx.player().hasPermission(Permissions.ADMIN) ? VaultUIContext.admin(ctx.player().getUniqueId()) : VaultUIContext.self(ctx.player().getUniqueId());
            new VaultScanMenu(ctx.player(), getParentMenu(), backCtx).open();
        });
        return builder.build();
    }

    @Override
    public void open() {
        setDialog(build());
        super.open();
    }

    /**
     * Opens the scan results menu for a selected region applying permission-based filtering.
     * <p>Context rules: ADMIN -> full (no owner filter); USER -> self-filter.</p>
     * @param player actor opening results
     * @param regionId target region identifier
     */
    private void openResultsFor(@NotNull Player player, String regionId) {
        WorldGuardService regions = plugin.worldGuard();
        boolean allowed = regions != null && regions.getRegionsIn(player.getWorld()).stream()
                .anyMatch(region -> region.id().equals(regionId) && mayScan(player, region));
        if (!allowed) {
            player.sendMessage(plugin.text().of(MessageKeys.SCAN_NOT_YOUR_REGION));
            return;
        }
        if (!checkCooldown(player)) return;
        VaultUIContext context = player.hasPermission(Permissions.ADMIN) ? VaultUIContext.admin(player.getUniqueId()) : VaultUIContext.self(player.getUniqueId());
        new LoadingMenu(player, getParentMenu(), Map.of("%player%", player.getName())).open();
        plugin.scanService().scan(player, regionId, context, results -> {
            if (results == null) {
                player.closeDialog();
                return;
            }
            new VaultScanMenu(player, getParentMenu(), context, regionId, results).open();
        });
    }

    /** Staff may scan any region; anyone else only one they own or are a member of. */
    static boolean mayScan(@NotNull Player player, @NotNull VaultRegion region) {
        return player.hasPermission(Permissions.ADMIN) || region.isPartOfRegion(player.getUniqueId());
    }

    private boolean checkCooldown(@NotNull Player player) {
        if (player.hasPermission(Permissions.ADMIN)) return true;
        var session = plugin.sessionManager().getOrCreate(player.getUniqueId());
        long now = System.currentTimeMillis();
        long last = session.getLastScanTime();
        long cooldownMs = plugin.settings().get().scan().cooldownSeconds() * 1000;
        if (now - last < cooldownMs) {
            long remaining = (cooldownMs - (now - last)) / 1000;
            player.sendMessage(plugin.text().of(MessageKeys.REGIONS_SCAN_COOLDOWN, Map.of("%time%", String.valueOf(remaining))));
            return false;
        }
        session.setLastScanTime(now);
        return true;
    }

    private List<VaultRegion> filterAndSort(@NotNull List<VaultRegion> all, String filter) {
        if (filter == null || filter.isBlank()) return all.stream().sorted(Comparator.comparing(VaultRegion::id, String.CASE_INSENSITIVE_ORDER)).toList();
        String f = filter.toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(r -> r.id().toLowerCase(Locale.ROOT).contains(f))
                .sorted(Comparator.comparing(VaultRegion::id, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}

