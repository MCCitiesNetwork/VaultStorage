package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.api.ui.ParentMenu;
import net.democracycraft.vault.internal.security.VaultCapturePolicy;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Child menu that lists regions in the current world with pagination and a text filter.
 * Selecting a region opens VaultScanMenu in results mode.
 */
public class VaultRegionListMenu extends ChildMenuImp {

    /** Menu configuration texts. */
    public static class Config implements Dto, java.io.Serializable {
        /** Dialog title. Placeholders: %player% %query% */
        public String title = "<gold><bold>Regions</bold></gold>";
        /** Header line above the list. Placeholders: %count% %page% %pages% %query% */
        public String header = "<gray>%count% regions</gray> <dark_gray>|</dark_gray> <gray>page %page%/%pages%</gray>";
        /** Button label for each region entry. Placeholder: %region% */
        public String regionBtn = "<white>%region%</white>";
        /** No results message. */
        public String noneFound = "<gray>No regions match.</gray>";
        /** Prev/Next page buttons. */
        public String prevPageBtn = "<gray><< Prev</gray>";
        public String nextPageBtn = "<gray>Next >></gray>";
        /** Back button to return to the parent scan menu. */
        public String backBtn = "<red><bold>Back</bold></red>";
        /** Error when services missing. */
        public String servicesMissing = "<red>Services not ready.</red>";
        /** Error when region not found (race condition). */
        public String regionNotFound = "<red>Region not found: %region%</red>";
    }

    /**
     * YAML header describing configuration placeholders.
     */
    private static final String HEADER = String.join("\n",
            "VaultRegionListMenu configuration.",
            "All strings accept MiniMessage or plain text.",
            "Placeholders:",
            "- %player% -> current player name (title)",
            "- %query%  -> current filter query (title, header)",
            "- %count%  -> total results (header)",
            "- %page%   -> current page number (1-based)",
            "- %pages%  -> total pages",
            "- %region% -> region id for buttons" );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultRegionListMenu", DataFolder.MENUS, HEADER);
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }
    private static Config cfg() { return YML.loadOrCreate(Config::new); }

    private static final int PAGE_SIZE = 6;

    private final String query; // used only in browse mode
    private final int page; // 0-based
    private final List<String> subsetIds; // when non-null, list only these region ids

    /** Browse mode: filter by query across all regions in world. */
    public VaultRegionListMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull String query, int page) {
        super(player, parent, "vault_region_list");
        this.query = query == null ? "" : query.trim();
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
        List<String> ids = new ArrayList<>(regionIds == null ? List.of() : regionIds);
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        this.subsetIds = ids;
        setDialog(build());
    }

    private Dialog build() {
        Config config = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> phTitle = Map.of("%player%", getPlayer().getName(), "%query%", query == null ? "" : query);
        builder.title(MiniMessageUtil.parseOrPlain(config.title, phTitle));
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
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.header, phHeader)));

            if (from < to) {
                for (int i = from; i < to; i++) {
                    String id = subsetIds.get(i);
                    Map<String,String> ph = Map.of("%region%", id);
                    builder.button(MiniMessageUtil.parseOrPlain(config.regionBtn, ph), ctx -> openResultsFor(ctx.player(), id));
                }
            } else {
                builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.noneFound)));
            }

            if (pages > 1) {
                if (currentPage > 0) builder.button(MiniMessageUtil.parseOrPlain(config.prevPageBtn), ctx -> new VaultRegionListMenu(ctx.player(), getParentMenu(), subsetIds, currentPage - 1).open());
                if (currentPage < pages - 1) builder.button(MiniMessageUtil.parseOrPlain(config.nextPageBtn), ctx -> new VaultRegionListMenu(ctx.player(), getParentMenu(), subsetIds, currentPage + 1).open());
            }

            builder.button(MiniMessageUtil.parseOrPlain(config.backBtn), ctx -> {
                VaultUIContext backCtx = VaultPermission.ADMIN.has(ctx.player()) ? VaultUIContext.admin(ctx.player().getUniqueId()) : VaultUIContext.self(ctx.player().getUniqueId());
                new VaultScanMenu(ctx.player(), getParentMenu(), backCtx).open();
            });
            return builder.build();
        }

        // Browse mode
        WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();
        if (wgs == null) {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.servicesMissing)));
            builder.button(MiniMessageUtil.parseOrPlain(config.backBtn), ctx -> new VaultScanMenu(ctx.player(), getParentMenu()).open());
            return builder.build();
        }

        World world = getPlayer().getWorld();
        List<VaultRegion> all = wgs.getRegionsIn(world);
        List<VaultRegion> filtered = filterAndSort(all, query);

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
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.header, phHeader)));

        if (from < to) {
            for (int i = from; i < to; i++) {
                VaultRegion r = filtered.get(i);
                Map<String,String> ph = Map.of("%region%", r.id());
                builder.button(MiniMessageUtil.parseOrPlain(config.regionBtn, ph), ctx -> openResultsFor(ctx.player(), r.id()));
            }
        } else {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.noneFound)));
        }

        if (pages > 1) {
            if (currentPage > 0) builder.button(MiniMessageUtil.parseOrPlain(config.prevPageBtn), ctx -> new VaultRegionListMenu(ctx.player(), getParentMenu(), query, currentPage - 1).open());
            if (currentPage < pages - 1) builder.button(MiniMessageUtil.parseOrPlain(config.nextPageBtn), ctx -> new VaultRegionListMenu(ctx.player(), getParentMenu(), query, currentPage + 1).open());
        }

        builder.button(MiniMessageUtil.parseOrPlain(config.backBtn), ctx -> {
            VaultUIContext backCtx = VaultPermission.ADMIN.has(ctx.player()) ? VaultUIContext.admin(ctx.player().getUniqueId()) : VaultUIContext.self(ctx.player().getUniqueId());
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
    private void openResultsFor(Player player, String regionId) {
        List<Block> blocks = computeEntries(player, regionId);
        if (blocks == null) return; // message already shown
        VaultUIContext context = VaultPermission.ADMIN.has(player) ? VaultUIContext.admin(player.getUniqueId()) : VaultUIContext.self(player.getUniqueId());
        new VaultScanMenu(player, getParentMenu(), context, regionId, blocks).open();
    }

    private List<VaultRegion> filterAndSort(List<VaultRegion> all, String filter) {
        if (filter == null || filter.isBlank()) return all.stream().sorted(Comparator.comparing(VaultRegion::id, String.CASE_INSENSITIVE_ORDER)).toList();
        String f = filter.toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(r -> r.id().toLowerCase(Locale.ROOT).contains(f))
                .sorted(Comparator.comparing(VaultRegion::id, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<Block> computeEntries(Player player, String regionId) {
        WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();
        BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
        if (wgs == null || bolt == null) {
            player.sendMessage(MiniMessageUtil.parseOrPlain(cfg().servicesMissing));
            return null;
        }
        List<VaultRegion> regions = wgs.getRegionsIn(player.getWorld());
        Optional<VaultRegion> target = regions.stream().filter(r -> r.id().equalsIgnoreCase(regionId)).findFirst();
        if (target.isEmpty()) {
            player.sendMessage(MiniMessageUtil.parseOrPlain(cfg().regionNotFound, Map.of("%region%", regionId)));
            return null;
        }
        VaultRegion region = target.get();
        BoundingBox box = region.boundingBox();
        List<Block> protectedBlocks = bolt.getProtectedBlocksIn(box, player.getWorld());
        List<Block> out = new ArrayList<>();
        boolean admin = VaultPermission.ADMIN.has(player);
        boolean actorOwnsRegion = region.isOwner(player.getUniqueId());
        UUID filterOwner = admin ? null : (actorOwnsRegion ? null : player.getUniqueId());
        // When actor is owner of the region, do NOT apply self-filter; rely solely on the centralized policy decision per block.
        for (Block b : protectedBlocks) {
            var decision = VaultCapturePolicy.evaluate(player, b);
            if (!decision.allowed()) continue;
            if (filterOwner != null) {
                UUID owner = bolt.getOwner(b);
                if (owner == null || !owner.equals(filterOwner)) continue;
            }
            out.add(b);
        }
        return out;
    }
}
