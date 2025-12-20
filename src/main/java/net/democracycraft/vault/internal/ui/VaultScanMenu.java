package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.region.VaultRegion;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.MojangService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.api.ui.ParentMenu;
import net.democracycraft.vault.internal.security.VaultCapturePolicy;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.popcraft.bolt.protection.EntityProtection;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Child menu dedicated to initiating a scan flow: search regions by query or use the current location (Here).
 * Region selection is handled in a separate child menu to keep buttons grouped and clean at the bottom.
 * <p>
 * Filtering rules (now apply to any Bolt-protected block, not just containers):
 * <ul>
 *   <li>When constructed via {@link VaultScanMenu#VaultScanMenu(Player, ParentMenu)} or with a self {@link VaultUIContext}, the scan is <b>owner-filtered</b> to the actor (only blocks whose Bolt owner equals the player).</li>
 *   <li>If an admin {@link VaultUIContext} without filterOwner is provided, results are not owner-filtered.</li>
 *   <li>All candidate blocks are validated by {@link VaultCapturePolicy} to enforce region/ownership rules.</li>
 * </ul>
 * Empty and non-container handling:
 * <ul>
 *   <li>Non-container blocks, and containers with empty inventory, behave like an "empty capture": Bolt protection is removed but no vault is persisted.</li>
 *   <li>Containers with items are vaulted and removed; remaining halves of double chests are re-protected.</li>
 * </ul>
 */
public class VaultScanMenu extends ChildMenuImp {

    public enum FilterMode {
        BLOCK,
        NON_CONTAINER,
        CONTAINER,
        ENTITY;

        public FilterMode next() {
            return switch (this) {
                case BLOCK -> NON_CONTAINER;
                case NON_CONTAINER -> CONTAINER;
                case CONTAINER -> ENTITY;
                case ENTITY -> BLOCK;
            };
        }
    }

    /** YAML-backed configuration for the scan UI and messages. */
    public static class Config implements Dto {
        /** Dialog title. Placeholders: %player% */
        public String title = "<gold><bold>Vault Scan</bold></gold>";
        /** Message when no results. */
        public String noneFound = "<gray>No protected blocks found in this region.</gray>";
        /** Error when services missing. */
        public String servicesMissing = "<red>Scan unavailable: services not ready.</red>";
        /** Results header. Placeholders: %region% %count% */
        public String resultsHeader = "<gold><bold>Region %region%</bold></gold> <gray>(%count% results)</gray>";
        /** Per-entry descriptive line. Placeholders: %x% %y% %z% %owner% %kind% %vaultable% */
        public String entryLine = "<gray>- %index% </gray><white>(%x%, %y%, %z%)</white> <gray>owner:</gray> <white>%owner%</white> <gray>|</gray> <white>%kind%</white> <gray>| vaultable:</gray> <white>%vaultable%</white>";
        /** Button label for vault action. */
        public String entryVaultButton = "<yellow>(vault) - %index%</yellow>";
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
        public String notAllowed = "<red>Not allowed by region/block rules.</red>";
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
        /** Toggle button label for the vault entry type filter. Visible only in results mode. Placeholder: %mode% */
        public String filterBtn = "<gray>[Filter: %mode%]</gray>";
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
    private final List<Block> entries; // only used in results mode (full set from computeEntries: containers + other protected blocks like paintings)
    /** Zero-based current page index in results mode. */
    private final int pageIndex;
    private final VaultUIContext uiContext;
    /** Current type filter applied in results mode. */
    private final FilterMode filterMode;


    /** Local cache for UUID->username to avoid repeated lookups per page. */
    private static final ConcurrentHashMap<UUID, String> NAME_CACHE = new ConcurrentHashMap<>();

    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent) {
        this(player, parent, new VaultUIContext(player.getUniqueId(), player.getUniqueId(), false));
    }

    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx) {
        super(player, parent, "vault_scan");
        this.uiContext = ctx;
        this.regionId = null;
        this.entries = List.of();
        this.pageIndex = 0;
        this.filterMode = FilterMode.BLOCK;
        setDialog(build());
    }

    /** Public constructor for results mode to allow opening from the region list menu. */
    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx, @NotNull String regionId, @NotNull List<Block> entries) {
        this(player, parent, ctx, regionId, entries, 0, FilterMode.BLOCK);
    }

    /** Results-mode constructor with explicit page index (zero-based). */
    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx, @NotNull String regionId, @NotNull List<Block> entries, int pageIndex) {
        this(player, parent, ctx, regionId, entries, pageIndex, FilterMode.BLOCK);
    }

    private VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx,
                          @NotNull String regionId, @NotNull List<Block> entries, int pageIndex,
                          @NotNull FilterMode filterMode) {
        super(player, parent, "vault_scan_results");
        this.uiContext = ctx;
        this.regionId = regionId;
        this.entries = entries;
        this.pageIndex = Math.max(0, pageIndex);
        this.filterMode = filterMode;
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
            builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(config.searchBtn), null, Duration.ofMinutes(5), 1, (player, response) -> {
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
        List<Block> visibleEntries = applyTypeFilter(entries, filterMode);
        int size = visibleEntries.size();
        int perPage = Math.max(1, config.pageSize);
        int totalPages = Math.max(1, (int) Math.ceil(size / (double) perPage));
        int currentPage = Math.min(pageIndex, totalPages - 1);
        int startIndex = currentPage * perPage;
        int endIndex = Math.min(startIndex + perPage, size);

        Map<String,String> placeholdersHeader = Map.of("%region%", regionId, "%count%", String.valueOf(visibleEntries.size()));
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.resultsHeader, placeholdersHeader)));

        // When ENTITY filter is active, render entity protections separately
        if (filterMode == FilterMode.ENTITY) {
            BoltService boltService = VaultStoragePlugin.getInstance().getBoltService();
            WorldGuardService worldGuardService = VaultStoragePlugin.getInstance().getWorldGuardService();
            if (boltService == null || worldGuardService == null) {
                builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.servicesMissing)));
                addFilterButton(builder, config, currentPage);
                return builder.build();
            }
            VaultRegion region = worldGuardService.getRegionById(regionId, getWorld());
            if (region == null) {
                builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.regionNotFound, Map.of("%region%", regionId))));
                addFilterButton(builder, config, currentPage);
                return builder.build();
            }
            Map<EntityProtection, Entity> protections = boltService.getProtectedEntities(getPlayer().getWorld(), region.boundingBox());
            int eSize = protections.size();
            if (eSize == 0) {
                builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain("<gray>No protected entities found in this region.</gray>")));
                addFilterButton(builder, config, currentPage);
                return builder.build();
            }
            int eTotalPages = Math.max(1, (int) Math.ceil(eSize / (double) perPage));
            int eCurrentPage = Math.min(currentPage, eTotalPages - 1);
            int eStart = eCurrentPage * perPage;
            int eEnd = Math.min(eStart + perPage, eSize);

            Set<UUID> toResolve = new HashSet<>();
            for (int i = eStart; i < eEnd; i++) {
                EntityProtection protection = protections.keySet().stream().toList().get(i);
                UUID ownerUuid = protection.getOwner();
                String ownerName;
                if (ownerUuid == null) {
                    ownerName = "unknown";
                } else {
                    String cached = NAME_CACHE.get(ownerUuid);
                    if (cached == null) {
                        ownerName = ownerUuid.toString().substring(0, 8);
                        toResolve.add(ownerUuid);
                    } else {
                        ownerName = cached;
                    }
                }
                String index = String.valueOf(i + 1);

                Location entityLoc = protections.get(protection).getLocation();

                String entityName = protections.get(protection).getType().name();

                var decision = VaultCapturePolicy.evaluate(getPlayer(), protections.get(protection));
                String vaultable = decision.allowed() ? cfg().vaultableYes : cfg().vaultableNo;

                Map<String,String> placeholdersEntry = Map.of(
                        "%x%", String.valueOf(entityLoc.getBlockX()),
                        "%y%", String.valueOf(entityLoc.getBlockY()),
                        "%z%", String.valueOf(entityLoc.getBlockZ()),
                        "%index%", index,
                        "%kind%", entityName,
                        "%owner%", ownerName,
                        "%vaultable%", vaultable
                );
                builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.entryLine, placeholdersEntry)));
                // Remove protection button for entity (calls BoltService#removeProtection with the EntityProtection instance)
                builder.sizableButton(MiniMessageUtil.parseOrPlain(config.entryVaultButton, placeholdersEntry), ctx -> {
                    var check = VaultCapturePolicy.evaluate(ctx.player(), protections.get(protection));
                    if (!check.allowed()) {
                        ctx.player().sendMessage(MiniMessageUtil.parseOrPlain(cfg().notAllowed));
                        return;
                    }

                    BoltService svc = VaultStoragePlugin.getInstance().getBoltService();
                    if (svc == null) return;
                    svc.removeProtection(protection);
                    // Refresh entity list after removal
                    new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, eCurrentPage, FilterMode.ENTITY).open();
                },100);
            }

            // Resolve names asynchronously if needed and refresh
            if (!toResolve.isEmpty()) {
                var plugin = VaultStoragePlugin.getInstance();
                new BukkitRunnable(){
                    @Override public void run(){
                        MojangService ms = plugin.getMojangService();
                        boolean updated = false;
                        if (ms != null) {
                            for (UUID u : toResolve) {
                                try {
                                    String name = ms.getUsername(u);
                                    if (name != null && !name.isBlank()) {
                                        NAME_CACHE.put(u, name);
                                        updated = true;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                        if (updated) {
                            new org.bukkit.scheduler.BukkitRunnable(){
                                @Override public void run(){
                                    new VaultScanMenu(getPlayer(), getParentMenu(), uiContext, regionId, entries, eCurrentPage, FilterMode.ENTITY).open();
                                }
                            }.runTask(plugin);
                        }
                    }
                }.runTaskAsynchronously(plugin);
            }

            // Navigation for entity pages
            if (eCurrentPage > 0) {
                builder.button(MiniMessageUtil.parseOrPlain(config.prevBtn), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, eCurrentPage - 1, FilterMode.ENTITY).open());
            }
            if (eCurrentPage < eTotalPages - 1) {
                builder.button(MiniMessageUtil.parseOrPlain(config.nextBtn), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, eCurrentPage + 1, FilterMode.ENTITY).open());
            }
            addFilterButton(builder, config, eCurrentPage);
            return builder.build();
        }

        if (visibleEntries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.noneFound)));
            addFilterButton(builder, config, currentPage);
            return builder.build();
        }

        // Page indicator
        Map<String,String> phPage = Map.of("%page%", String.valueOf(currentPage + 1), "%total%", String.valueOf(totalPages));
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.pageLabel, phPage)));

        BoltService boltService = VaultStoragePlugin.getInstance().getBoltService();
        Set<UUID> toResolve = new HashSet<>();
        for (int i = startIndex; i < endIndex; i++) {
            Block entryBlock = visibleEntries.get(i);
            UUID ownerUuid = boltService != null ? boltService.getOwner(entryBlock) : null;
            String ownerName;
            if (ownerUuid == null) {
                ownerName = "unknown";
            } else {
                String cached = NAME_CACHE.get(ownerUuid);
                if (cached == null) {
                    ownerName = ownerUuid.toString().substring(0, 8);
                    toResolve.add(ownerUuid);
                } else {
                    ownerName = cached;
                }
            }
            String kind = (entryBlock.getState() instanceof Container) ? "container" : "block";
            var decision = VaultCapturePolicy.evaluate(getPlayer(), entryBlock);
            String vaultable = decision.allowed() ? cfg().vaultableYes : cfg().vaultableNo;
            Map<String,String> placeholdersEntry = Map.of(
                    "%x%", String.valueOf(entryBlock.getX()), "%y%", String.valueOf(entryBlock.getY()), "%z%", String.valueOf(entryBlock.getZ()),
                    "%owner%", ownerName,
                    "%kind%", kind,
                    "%index%", String.valueOf(i + 1),
                    "%vaultable%", vaultable
            );
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(config.entryLine, placeholdersEntry)));
            // Vault button for this entry
            builder.button(MiniMessageUtil.parseOrPlain(config.entryVaultButton, placeholdersEntry), ctx -> {
                // Show a loading menu while we process DB work
                new LoadingMenu(ctx.player(), getParentMenu(), Map.of("%player%", ctx.player().getName())).open();
                Block targetBlock = ctx.player().getWorld().getBlockAt(entryBlock.getX(), entryBlock.getY(), entryBlock.getZ());
                VaultCaptureService capSvc = VaultStoragePlugin.getInstance().getCaptureService();
                VaultCaptureService.SessionTexts texts = capSvc.sessionTexts();
                capSvc.captureDirectAsync(ctx.player(), targetBlock, texts, success -> {
                    // Refresh results after capture attempt
                    List<Block> recomputed = computeEntries(ctx.player(), regionId, config);
                    if (recomputed == null) return;
                    int newTotal = Math.max(1, (int) Math.ceil(recomputed.size() / (double) Math.max(1, config.pageSize)));
                    int safePage = Math.min(currentPage, newTotal - 1);
                    new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, recomputed, safePage).open();
                });
            });
            // Teleport button for this entry (literal coordinates with safe centering)
            builder.button(MiniMessageUtil.parseOrPlain(config.entryTeleportButton, placeholdersEntry), ctx -> {
                boolean passable = entryBlock.isPassable();
                double destY = passable ? entryBlock.getY() : (entryBlock.getY() + 1);
                Location destination = new Location(ctx.player().getWorld(), entryBlock.getX() + 0.5, destY, entryBlock.getZ() + 0.5, ctx.player().getLocation().getYaw(), ctx.player().getLocation().getPitch());
                ctx.player().teleport(destination);
            });
        }

        // Async resolve missing owner names and refresh the page if any were resolved
        if (!toResolve.isEmpty()) {
            var plugin = VaultStoragePlugin.getInstance();
            new BukkitRunnable(){
                @Override public void run(){
                    MojangService ms = plugin.getMojangService();
                    boolean updated = false;
                    if (ms != null) {
                        for (UUID u : toResolve) {
                            try {
                                String name = ms.getUsername(u);
                                if (name != null && !name.isBlank()) {
                                    NAME_CACHE.put(u, name);
                                    updated = true;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (updated) {
                        new org.bukkit.scheduler.BukkitRunnable(){
                            @Override public void run(){
                                new VaultScanMenu(getPlayer(), getParentMenu(), uiContext, regionId, entries, currentPage).open();
                            }
                        }.runTask(plugin);
                    }
                }
            }.runTaskAsynchronously(plugin);
        }

        // Navigation buttons
        if (currentPage > 0) {
            builder.button(MiniMessageUtil.parseOrPlain(config.prevBtn), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, currentPage - 1, filterMode).open());
        }
        if (currentPage < totalPages - 1) {
            builder.button(MiniMessageUtil.parseOrPlain(config.nextBtn), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, currentPage + 1, filterMode).open());
        }

        addFilterButton(builder, config, currentPage);

        return builder.build();
    }

    private void addFilterButton(AutoDialog.Builder builder, Config config, int currentPage) {
        String modeLabel = switch (filterMode) {
            case BLOCK -> "Block";
            case NON_CONTAINER -> "Non-Container";
            case CONTAINER -> "Container";
            case ENTITY -> "Entity";
        };
        Map<String,String> phFilter = Map.of("%mode%", modeLabel);
        builder.sizableButton(MiniMessageUtil.parseOrPlain(config.filterBtn, phFilter), ctx -> {
            FilterMode next = filterMode.next();
            new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, currentPage, next).open();
        }, 100);
    }

    private List<Block> applyTypeFilter(List<Block> source, FilterMode mode) {
        // In ENTITY filter mode, block list is not used; entity protections are rendered separately.
        if (mode == FilterMode.BLOCK || mode == FilterMode.ENTITY) return source;
        List<Block> out = new ArrayList<>();
        boolean wantContainers = (mode == FilterMode.CONTAINER);
        for (Block b : source) {
            boolean isContainer = b.getState() instanceof Container;
            if (wantContainers && isContainer) {
                out.add(b);
            } else if (!wantContainers && !isContainer) {
                out.add(b);
            }
        }
        return out;
    }

    private @NotNull World getWorld() {
        return getPlayer().getWorld();
    }

    @Override
    public void open() {
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
     * <p>Owner filter: If {@link VaultUIContext#filterOwner()} is non-null, only blocks whose Bolt owner matches that UUID are considered.</p>
     * <p>Policy: Each protected block is evaluated with {@link net.democracycraft.vault.internal.security.VaultCapturePolicy#evaluate(Player, Block)}; only allowed decisions pass.</p>
     * <p>Unprotected blocks are only included when the policy returns allowed (e.g. override).</p>
     *
     * <p><strong>Region-owner behavior:</strong> When the actor is an owner of the target region, the owner filter is disabled
     * (even if {@code filterOwner} is present) and the centralized policy fully determines visibility and eligibility.
     * For non-owners, the owner filter remains enforced so they only see their own vaultable blocks.</p>
     *
     * @param player   actor performing the scan
     * @param regionId region identifier to scan within
     * @param config   active configuration snapshot
     * @return list of vaultable blocks (may be empty), or null if services missing / region not found
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

