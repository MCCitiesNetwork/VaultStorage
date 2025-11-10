package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.api.ui.ParentMenu;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.security.VaultCapturePolicy;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Child menu dedicated to initiating a scan flow: search regions by query or use the current location (Here).
 * Region selection is handled in a separate child menu to keep buttons grouped and clean at the bottom.
 * <p>
 * Filtering rules:
 * <ul>
 *   <li>When constructed via {@link VaultScanMenu#VaultScanMenu(Player, ParentMenu)} or with a self {@link VaultUIContext}, the scan is <b>owner-filtered</b> to the actor (only containers whose Bolt owner equals the player).</li>
 *   <li>If an admin {@link VaultUIContext} without filterOwner is provided, results are not owner-filtered.</li>
 *   <li>All candidate containers are additionally validated by the centralized {@link net.democracycraft.vault.internal.security.VaultCapturePolicy} to enforce region/ownership rules.</li>
 * </ul>
 * Empty container handling:
 * <ul>
 *   <li>When vaulting from the scan results, empty containers are skipped (block removed, protection removed) and the configurable message {@code emptyCaptureSkipped} is shown.</li>
 * </ul>
 */
public class VaultScanMenu extends ChildMenuImp {

    /** YAML-backed configuration for the scan UI and messages. */
    public static class Config implements Dto {
        /** Dialog title. Placeholders: %player% */
        public String title = "<gold><bold>Vault Scan</bold></gold>";
        /** Message when no results. */
        public String noneFound = "<gray>No protected containers found in this region.</gray>";
        /** Error when services missing. */
        public String servicesMissing = "<red>Scan unavailable: services not ready.</red>";
        /** Error when target block is not a container. */
        public String notAContainer = "<red>Target is not a container.</red>";
        /** Message after successful vault action for an entry. */
        public String vaultedOk = "Container vaulted.";
        /** Message when the target container is empty and nothing is persisted. */
        public String emptyCaptureSkipped = "Container empty; nothing captured.";
        /** Results header. Placeholders: %region% %count% */
        public String resultsHeader = "<gold><bold>Region %region%</bold></gold> <gray>(%count% results)</gray>";
        /** Per-entry descriptive line. Placeholders: %x% %y% %z% %owner% */
        public String entryLine = "<gray>- </gray><white>(%x%, %y%, %z%)</white> <gray>owner:</gray> <white>%owner%</white>";
        /** Button label for vault action. */
        public String entryVaultButton = "<yellow>(vault)</yellow>";
        /** Button label for teleport action. */
        public String entryTeleportButton = "<aqua>(teleport)</aqua>";
        // Browse/search controls
        /** Label for region search/filter input. */
        public String searchLabel = "<gray>Search regions</gray>";
        /** Button to apply region filter and open the list menu. */
        public String searchBtn = "<aqua>Search</aqua>";
        /** Button to detect the region at the player's current location. */
        public String hereBtn = "<yellow>Here</yellow>";
        /** Error when region not found. */
        public String regionNotFound = "<red>Region not found: %region%</red>";
        /** Message when Bolt has no owner and the player becomes the vault owner. */
        public String noBoltOwner = "<yellow>No Bolt owner found; you will be set as the vault owner.</yellow>";
        /** Message when actor not allowed to vault an entry (overlapping-region policy). */
        public String notAllowed = "<red>Not allowed by region/container rules.</red>";
        /** Optional per-entry vaultable flag yes. */
        public String vaultableYes = "yes";
        /** Optional per-entry vaultable flag no. */
        public String vaultableNo = "no";
        /**
         * Maximum number of entries to render per page when showing results.
         * Keep this reasonably small to avoid dialog overflow and client lag.
         */
        public int pageSize = 5;
        /** Button label for navigating to the previous page. */
        public String prevBtn = "<yellow>< Prev</yellow>";
        /** Button label for navigating to the next page. */
        public String nextBtn = "<yellow>Next ></yellow>";
        /** Label shown indicating the current page and total pages. Placeholders: %page% %total% */
        public String pageLabel = "<gray>Page %page% / %total%</gray>";
    }

    private static final String HEADER = String.join("\n",
            "VaultScanMenu configuration.",
            "Placeholders:",
            "- %player% -> actor/player name",
            "- %region% -> region id",
            "- %count% -> number of results",
            "- %x% %y% %z% %owner% -> entry values",
            "Fields:",
            "- emptyCaptureSkipped -> message when a vaulted container was empty and persistence skipped"
    );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultScanMenu", DataFolder.MENUS, HEADER);
    private static Config cfg() { return YML.loadOrCreate(Config::new); }
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }

    private final String regionId; // null -> browse/search mode; non-null -> results mode
    private final List<Block> entries; // only used in results mode
    /** Zero-based current page index in results mode. */
    private final int pageIndex;
    private final VaultUIContext uiContext;

    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent) {
        this(player, parent, new VaultUIContext(player.getUniqueId(), player.getUniqueId(), false));
    }

    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx) {
        super(player, parent, "vault_scan");
        this.uiContext = ctx;
        this.regionId = null;
        this.entries = List.of();
        this.pageIndex = 0;
        setDialog(build());
    }

    /** Public constructor for results mode to allow opening from the region list menu. */
    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx, @NotNull String regionId, @NotNull List<Block> entries) {
        super(player, parent, "vault_scan_results");
        this.uiContext = ctx;
        this.regionId = regionId;
        this.entries = entries;
        this.pageIndex = 0;
        setDialog(build());
    }

    /** Results-mode constructor with explicit page index (zero-based). */
    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx, @NotNull String regionId, @NotNull List<Block> entries, int pageIndex) {
        super(player, parent, "vault_scan_results");
        this.uiContext = ctx;
        this.regionId = regionId;
        this.entries = entries;
        this.pageIndex = Math.max(0, pageIndex);
        setDialog(build());
    }

    private Dialog build() {
        Config config = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> placeholdersSelf = Map.of("%player%", getPlayer().getName());
        builder.title(MiniMessageUtil.parseOrPlain(config.title, placeholdersSelf));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        if (regionId == null) {
            // Browse/search root: only Search input/button and Here. No region listing here.
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.searchLabel)));
            builder.addInput(DialogInput.text("QUERY", MiniMessageUtil.parseOrPlain(config.searchLabel)).labelVisible(true).build());
            builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(config.searchBtn), null, java.time.Duration.ofMinutes(5), 1, (player, response) -> {
                String q = Optional.ofNullable(response.getText("QUERY")).orElse("").trim();
                // VaultRegionListMenu does not accept uiContext; use its (player, parent, query, page) constructor
                new VaultRegionListMenu(player, getParentMenu(), q, 0).open();
            });

            // "Here" button: detect region at player's current location and open results or list if multiple
            builder.button(MiniMessageUtil.parseOrPlain(config.hereBtn), ctx -> {
                WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();
                if (wgs == null) {
                    ctx.player().sendMessage(MiniMessageUtil.parseOrPlain(config.servicesMissing));
                    return;
                }
                var all = wgs.getRegionsIn(ctx.player().getWorld());
                var loc = ctx.player().getLocation();
                List<VaultRegion> containing = all.stream().filter(r -> r.contains(loc.getX(), loc.getY(), loc.getZ())).toList();
                if (containing.isEmpty()) {
                    ctx.player().sendMessage(MiniMessageUtil.parseOrPlain("<red>No region at your location.</red>"));
                    return;
                }
                if (containing.size() > 1) {
                    List<String> ids = containing.stream().map(VaultRegion::id).sorted(String.CASE_INSENSITIVE_ORDER).toList();
                    // VaultRegionListMenu subset constructor: (player, parent, ids, page)
                    new VaultRegionListMenu(ctx.player(), getParentMenu(), ids, 0).open();
                    return;
                }
                VaultRegion only = containing.getFirst();
                List<Block> blocks = computeEntries(ctx.player(), only.id(), config);
                if (blocks == null) return;
                // Pass uiContext into results-mode constructor
                new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, only.id(), blocks, 0).open();
            });

            return builder.build();
        }

        // Results mode
        int size = entries.size();
        int perPage = Math.max(1, config.pageSize);
        int totalPages = Math.max(1, (int) Math.ceil(size / (double) perPage));
        int currentPage = Math.min(pageIndex, totalPages - 1);
        int startIndex = currentPage * perPage;
        int endIndex = Math.min(startIndex + perPage, size);

        Map<String,String> placeholdersHeader = Map.of("%region%", regionId, "%count%", String.valueOf(entries.size()));
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.resultsHeader, placeholdersHeader)));
        if (entries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.noneFound)));
            return builder.build();
        }

        // Page indicator
        Map<String,String> phPage = Map.of("%page%", String.valueOf(currentPage + 1), "%total%", String.valueOf(totalPages));
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.pageLabel, phPage)));

        BoltService boltService = VaultStoragePlugin.getInstance().getBoltService();
        for (int i = startIndex; i < endIndex; i++) {
            Block entryBlock = entries.get(i);
            UUID ownerUuid = boltService != null ? boltService.getOwner(entryBlock) : null;
            String ownerName = ownerUuid == null ? "unknown" : Optional.ofNullable(Bukkit.getOfflinePlayer(ownerUuid).getName()).orElse(ownerUuid.toString());
            Map<String,String> placeholdersEntry = Map.of(
                    "%x%", String.valueOf(entryBlock.getX()), "%y%", String.valueOf(entryBlock.getY()), "%z%", String.valueOf(entryBlock.getZ()),
                    "%owner%", ownerName
            );
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.entryLine, placeholdersEntry)));
            // Vault button for this entry
            builder.button(MiniMessageUtil.parseOrPlain(config.entryVaultButton, placeholdersEntry), ctx -> {
                // Show a loading menu while we process DB work
                new LoadingMenu(ctx.player(), getParentMenu(), Map.of("%player%", ctx.player().getName())).open();
                new BukkitRunnable() {
                    @Override public void run() {
                        Block targetBlock = ctx.player().getWorld().getBlockAt(entryBlock.getX(), entryBlock.getY(), entryBlock.getZ());
                        if (!(targetBlock.getState() instanceof Container)) {
                            ctx.player().sendMessage(MiniMessageUtil.parseOrPlain(config.notAContainer));
                            return;
                        }
                        BoltService boltSvc = VaultStoragePlugin.getInstance().getBoltService();
                        UUID originalOwner = null;
                        if (boltSvc != null) { try { originalOwner = boltSvc.getOwner(targetBlock); } catch (Throwable ignored) {} }

                        // Re-evaluate authorization via centralized policy (membership may have changed)
                        VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluateWithLog(ctx.player(), targetBlock, "ScanEntryCheck");
                        if (!decision.allowed()) {
                            ctx.player().sendMessage(MiniMessageUtil.parseOrPlain(config.notAllowed));
                            return;
                        }

                        if (boltSvc != null && originalOwner == null && decision.hasOverride()) {
                            ctx.player().sendMessage(MiniMessageUtil.parseOrPlain(config.noBoltOwner));
                        }
                        if (boltSvc != null) { try { boltSvc.removeProtection(targetBlock); } catch (Throwable ignored) {} }

                        VaultCaptureService vaultCaptureService = VaultStoragePlugin.getInstance().getCaptureService();
                        // Pre-check emptiness: if empty, skip vaulting (do not remove block) and just refresh list.
                        boolean empty;
                        try { empty = vaultCaptureService.isContainerEmpty(targetBlock); } catch (IllegalArgumentException ex) { empty = true; }
                        if (empty) {
                            ctx.player().sendMessage(MiniMessageUtil.parseOrPlain(config.emptyCaptureSkipped));
                            List<Block> recomputed = computeEntries(ctx.player(), regionId, config);
                            if (recomputed == null) return;
                            int newTotal = Math.max(1, (int) Math.ceil(recomputed.size() / (double) Math.max(1, config.pageSize)));
                            int safePage = Math.min(currentPage, newTotal - 1);
                            new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, recomputed, safePage).open();
                            return;
                        }

                        VaultImp vault = vaultCaptureService.captureFromBlock(ctx.player(), targetBlock);
                        var plugin = VaultStoragePlugin.getInstance();
                        UUID finalOwner = originalOwner != null ? originalOwner : ctx.player().getUniqueId();
                        new BukkitRunnable() {
                            @Override public void run() {
                                var vaultService = plugin.getVaultService();
                                UUID worldUuid = targetBlock.getWorld().getUID();
                                UUID newId;
                                {
                                    var existing = vaultService.findByLocation(worldUuid, entryBlock.getX(), entryBlock.getY(), entryBlock.getZ());
                                    if (existing != null) { vaultService.delete(existing.uuid); }
                                    var created = vaultService.createVault(worldUuid, entryBlock.getX(), entryBlock.getY(), entryBlock.getZ(), finalOwner,
                                            vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                            vault.blockDataString());
                                    newId = created.uuid;
                                }
                                List<ItemStack> items = vault.contents();
                                // Batch persist items to minimize DB round-trips
                                List<VaultItemEntity> batch = new java.util.ArrayList<>(items.size());
                                for (int idx = 0; idx < items.size(); idx++) {
                                    ItemStack itemStack = items.get(idx);
                                    if (itemStack == null) continue;
                                    VaultItemEntity vie = new VaultItemEntity();
                                    vie.vaultUuid = newId;
                                    vie.slot = idx;
                                    vie.amount = itemStack.getAmount();
                                    vie.item = ItemSerialization.toBytes(itemStack);
                                    batch.add(vie);
                                }
                                if (!batch.isEmpty()) {
                                    vaultService.putItems(newId, batch);
                                }
                                new BukkitRunnable() { @Override public void run() {
                                    ctx.player().sendMessage(MiniMessageUtil.parseOrPlain(config.vaultedOk));
                                    List<Block> recomputed = computeEntries(ctx.player(), regionId, config);
                                    if (recomputed == null) return;
                                    int newTotal = Math.max(1, (int) Math.ceil(recomputed.size() / (double) Math.max(1, config.pageSize)));
                                    int safePage = Math.min(currentPage, newTotal - 1);
                                    // Include uiContext when re-opening results
                                    new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, recomputed, safePage).open();
                                } }.runTask(plugin);
                            }
                        }.runTaskAsynchronously(plugin);
                    }
                }.runTask(VaultStoragePlugin.getInstance());
            });
            // Teleport button for this entry (literal coordinates with safe centering)
            builder.button(MiniMessageUtil.parseOrPlain(config.entryTeleportButton, placeholdersEntry), ctx -> {
                boolean passable = entryBlock.isPassable();
                double destY = passable ? entryBlock.getY() : (entryBlock.getY() + 1);
                Location destination = new Location(ctx.player().getWorld(), entryBlock.getX() + 0.5, destY, entryBlock.getZ() + 0.5, ctx.player().getLocation().getYaw(), ctx.player().getLocation().getPitch());
                ctx.player().teleport(destination);
            });
        }

        // Navigation buttons
        if (currentPage > 0) {
            builder.button(MiniMessageUtil.parseOrPlain(config.prevBtn), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, currentPage - 1).open());
        }
        if (currentPage < totalPages - 1) {
            builder.button(MiniMessageUtil.parseOrPlain(config.nextBtn), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, currentPage + 1).open());
        }

        return builder.build();
    }

    @Override
    public void open() {
        // Rebuild the dialog to reflect current YAML configuration on each open
        setDialog(build());
        super.open();
    }

    private static double volumeOf(VaultRegion r) {
        BoundingBox b = r.boundingBox();
        return Math.max(0, (b.getMaxX() - b.getMinX()))
                * Math.max(0, (b.getMaxY() - b.getMinY()))
                * Math.max(0, (b.getMaxZ() - b.getMinZ()));
    }

    /**
     * Computes candidate blocks in the region that the actor is allowed to vault per capture policy.
     * <p>Owner filter: If {@link VaultUIContext#filterOwner()} is non-null, only containers whose Bolt owner matches that UUID are considered.</p>
     * <p>Policy: Each protected block is evaluated with {@link net.democracycraft.vault.internal.security.VaultCapturePolicy#evaluate(Player, Block)}; only allowed decisions pass.</p>
     * <p>Unprotected containers are only included when the policy returns allowed (e.g. override).</p>
     *
     * <p><strong>Region-owner behavior:</strong> When the actor is an owner of the target region, the owner filter is disabled
     * (even if {@code filterOwner} is present) and the centralized policy fully determines visibility and eligibility.
     * For non-owners, the owner filter remains enforced so they only see their own vaultable containers.</p>
     *
     * @param player   actor performing the scan
     * @param regionId region identifier to scan within
     * @param config   active configuration snapshot
     * @return list of vaultable container blocks (may be empty), or null if services missing / region not found
     */
    private List<Block> computeEntries(Player player, String regionId, Config config) {
        World world = player.getWorld();
        WorldGuardService worldGuardService = VaultStoragePlugin.getInstance().getWorldGuardService();
        BoltService boltService = VaultStoragePlugin.getInstance().getBoltService();
        if (worldGuardService == null || boltService == null) {
            player.sendMessage(MiniMessageUtil.parseOrPlain(config.servicesMissing));
            return null;
        }
        var regions = worldGuardService.getRegionsIn(world);
        var target = regions.stream().filter(r -> r.id().equalsIgnoreCase(regionId)).findFirst();
        if (target.isEmpty()) {
            player.sendMessage(MiniMessageUtil.parseOrPlain(config.regionNotFound, Map.of("%region%", regionId)));
            return null;
        }
        var region = target.get();
        boolean actorOwnsRegion = region.isOwner(player.getUniqueId());
        BoundingBox boundingBox = region.boundingBox();
        List<Block> protectedBlocks = boltService.getProtectedBlocksIn(boundingBox, world);
        List<Block> entryBlocks = new ArrayList<>();
        for (Block block : protectedBlocks) {
            // Central policy decides per-block allowance
            VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(player, block);
            if (!decision.allowed()) continue;
            // Apply owner filter only when actor is NOT a region owner
            if (!actorOwnsRegion && uiContext.filterOwner() != null) {
                UUID owner = boltService.getOwner(block);
                if (owner == null || !owner.equals(uiContext.filterOwner())) continue;
            }
            entryBlocks.add(block);
        }
        return entryBlocks;
    }
}

