package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.config.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.scan.ScanResult;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.bolt.BoltService;
import com.minecraftcitiesnetwork.vaultstorage.region.WorldGuardService;
import com.minecraftcitiesnetwork.vaultstorage.menu.AutoDialog;
import com.minecraftcitiesnetwork.vaultstorage.menu.ParentMenu;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCapturePolicy;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCaptureService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.popcraft.bolt.protection.EntityProtection;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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



    /** Scan results shown on one page: few enough for a dialog. */
    private static final int RESULTS_PER_PAGE = 5;

    private final String regionId; // null -> browse/search mode; non-null -> results mode
    private final List<ScanResult> entries; // only used in results mode (full set from computeEntries: containers + other protected blocks like paintings)
    /** Zero-based current page index in results mode. */
    private final int pageIndex;
    private final VaultUIContext uiContext;
    /** Current type filter applied in results mode. */
    private final FilterMode filterMode;


    /** Local cache for UUID->username to avoid repeated lookups per page. */
    private static final ConcurrentHashMap<UUID, String> NAME_CACHE = new ConcurrentHashMap<>();

    /**
     * Records an owner's name for the result lists. The scan service resolves names before it
     * hands results over, so a list is shown once, complete. It used to be shown with short ids
     * and then opened again when the names arrived, on top of whatever the player had moved to.
     */
    public static void rememberName(@NotNull UUID owner, @NotNull String name) {
        NAME_CACHE.put(owner, name);
    }

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
    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx, @NotNull String regionId, @NotNull List<ScanResult> entries) {
        this(player, parent, ctx, regionId, entries, 0, FilterMode.BLOCK);
    }

    /** Results-mode constructor with explicit page index (zero-based). */
    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx, @NotNull String regionId, @NotNull List<ScanResult> entries, int pageIndex) {
        this(player, parent, ctx, regionId, entries, pageIndex, FilterMode.BLOCK);
    }

    private VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx,
                          @NotNull String regionId, @NotNull List<ScanResult> entries, int pageIndex,
                          @NotNull FilterMode filterMode) {
        super(player, parent, "vault_scan_results");
        this.uiContext = ctx;
        this.regionId = regionId;
        this.entries = entries;
        this.pageIndex = Math.max(0, pageIndex);
        this.filterMode = filterMode;
        setDialog(build());
    }

    private boolean checkCooldown(Player player) {
        if (player.hasPermission("vaultstorage.admin")) return true;
        var session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId());
        long now = System.currentTimeMillis();
        long last = session.getLastScanTime();
        long cooldownMs = VaultStoragePlugin.getInstance().settings().get().scan().cooldownSeconds() * 1000;
        if (now - last < cooldownMs) {
            long remaining = (cooldownMs - (now - last)) / 1000;
            player.sendMessage(Text.of(MessageKeys.SCAN_SCAN_COOLDOWN, Map.of("%time%", String.valueOf(remaining))));
            return false;
        }
        session.setLastScanTime(now);
        return true;
    }

    private Dialog build() {
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> placeholdersSelf = Map.of("%player%", getPlayer().getName());
        builder.title(Text.of(MessageKeys.SCAN_TITLE, placeholdersSelf));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        if (regionId == null) {
            // Browse/search root: only Search input/button and Here. No region listing here.
            builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_SEARCH_LABEL)));
            builder.addInput(DialogInput.text("QUERY", Text.of(MessageKeys.SCAN_SEARCH_LABEL)).labelVisible(true).build());
            builder.buttonWithPlayer(Text.of(MessageKeys.SCAN_SEARCH_BTN), null, Duration.ofMinutes(5), 1, (player, response) -> {
                String q = Optional.ofNullable(response.getText("QUERY")).orElse("").trim();
                // VaultRegionListMenu does not accept uiContext; use its (player, parent, query, page) constructor
                new VaultRegionListMenu(player, getParentMenu(), q, 0).open();
            });

            // "Here" button: detect region at player's current location and open results or list if multiple
            builder.button(Text.of(MessageKeys.SCAN_HERE_BTN), ctx -> {
                WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();
                if (wgs == null) {
                    ctx.player().sendMessage(Text.of(MessageKeys.SCAN_SERVICES_MISSING));
                    return;
                }
                var all = wgs.getRegionsIn(ctx.player().getWorld());
                var loc = ctx.player().getLocation();
                List<VaultRegion> containing = all.stream().filter(r -> r.contains(loc.getX(), loc.getY(), loc.getZ())).toList();
                if (containing.isEmpty()) {
                    ctx.player().sendMessage(Text.of(MessageKeys.SCAN_NO_REGION_HERE));
                    return;
                }
                if (containing.size() > 1) {
                    List<String> ids = containing.stream().map(VaultRegion::id).sorted(String.CASE_INSENSITIVE_ORDER).toList();
                    // VaultRegionListMenu subset constructor: (player, parent, ids, page)
                    new VaultRegionListMenu(ctx.player(), getParentMenu(), ids, 0).open();
                    return;
                }
                VaultRegion only = containing.getFirst();
                if (!checkCooldown(ctx.player())) return;
                new LoadingMenu(ctx.player(), getParentMenu(), Map.of("%player%", ctx.player().getName())).open();
                VaultStoragePlugin.getInstance().getScanService().scan(ctx.player(), only.id(), uiContext, blocks -> {
                    if (blocks == null) return;
                    new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, only.id(), blocks, 0).open();
                });
            });

            return builder.build();
        }

        // Results mode
        List<ScanResult> visibleEntries = applyTypeFilter(entries, filterMode);
        int size = visibleEntries.size();
        int perPage = Math.max(1, RESULTS_PER_PAGE);
        int totalPages = Math.max(1, (int) Math.ceil(size / (double) perPage));
        int currentPage = Math.min(pageIndex, totalPages - 1);
        int startIndex = currentPage * perPage;
        int endIndex = Math.min(startIndex + perPage, size);

        Map<String,String> placeholdersHeader = Map.of("%region%", regionId, "%count%", String.valueOf(visibleEntries.size()));
        builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_RESULTS_HEADER, placeholdersHeader)));

        // When ENTITY filter is active, render entity protections separately
        if (filterMode == FilterMode.ENTITY) {
            BoltService boltService = VaultStoragePlugin.getInstance().getBoltService();
            WorldGuardService worldGuardService = VaultStoragePlugin.getInstance().getWorldGuardService();
            if (boltService == null || worldGuardService == null) {
                builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_SERVICES_MISSING)));
                addFilterButton(builder, currentPage);
                return builder.build();
            }
            VaultRegion region = worldGuardService.getRegionById(regionId, getWorld());
            if (region == null) {
                builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_REGION_NOT_FOUND, Map.of("%region%", regionId))));
                addFilterButton(builder, currentPage);
                return builder.build();
            }
            Map<EntityProtection, Entity> protections = boltService.getProtectedEntities(getPlayer().getWorld(), region.boundingBox());
            int eSize = protections.size();
            if (eSize == 0) {
                builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_NO_ENTITIES)));
                addFilterButton(builder, currentPage);
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
                String vaultable = Text.plain(decision.allowed() ? MessageKeys.SCAN_VAULTABLE_YES : MessageKeys.SCAN_VAULTABLE_NO);

                Map<String,String> placeholdersEntry = Map.of(
                        "%x%", String.valueOf(entityLoc.getBlockX()),
                        "%y%", String.valueOf(entityLoc.getBlockY()),
                        "%z%", String.valueOf(entityLoc.getBlockZ()),
                        "%index%", index,
                        "%kind%", entityName,
                        "%owner%", ownerName,
                        "%vaultable%", vaultable
                );
                builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_ENTRY_LINE, placeholdersEntry)));
                // Remove protection button for entity (calls BoltService#removeProtection with the EntityProtection instance)
                builder.sizableButton(Text.of(MessageKeys.SCAN_ENTRY_VAULT_BUTTON, placeholdersEntry), ctx -> {
                    var check = VaultCapturePolicy.evaluate(ctx.player(), protections.get(protection));
                    if (!check.allowed()) {
                        ctx.player().sendMessage(Text.of(MessageKeys.SCAN_NOT_ALLOWED));
                        return;
                    }

                    BoltService svc = VaultStoragePlugin.getInstance().getBoltService();
                    if (svc == null) return;
                    svc.removeProtection(protection);
                    // Refresh entity list after removal
                    new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, eCurrentPage, FilterMode.ENTITY).open();
                },100);
            }

            // Navigation for entity pages
            if (eCurrentPage > 0) {
                builder.button(Text.of(MessageKeys.SCAN_PREV_BTN), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, eCurrentPage - 1, FilterMode.ENTITY).open());
            }
            if (eCurrentPage < eTotalPages - 1) {
                builder.button(Text.of(MessageKeys.SCAN_NEXT_BTN), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, eCurrentPage + 1, FilterMode.ENTITY).open());
            }
            addFilterButton(builder, eCurrentPage);
            return builder.build();
        }

        if (visibleEntries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_NONE_FOUND)));
            addFilterButton(builder, currentPage);
            return builder.build();
        }

        // Page indicator
        Map<String,String> phPage = Map.of("%page%", String.valueOf(currentPage + 1), "%total%", String.valueOf(totalPages));
        builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_PAGE_LABEL, phPage)));

        Set<UUID> toResolve = new HashSet<>();
        for (int i = startIndex; i < endIndex; i++) {
            ScanResult entry = visibleEntries.get(i);
            Block entryBlock = entry.block();
            UUID ownerUuid = entry.owner();
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
            String kind = isContainerMaterial(entry.type()) ? "container" : "block";
            String vaultable = Text.plain(MessageKeys.SCAN_VAULTABLE_YES);
            Map<String,String> placeholdersEntry = Map.of(
                    "%x%", String.valueOf(entryBlock.getX()), "%y%", String.valueOf(entryBlock.getY()), "%z%", String.valueOf(entryBlock.getZ()),
                    "%owner%", ownerName,
                    "%kind%", kind,
                    "%index%", String.valueOf(i + 1),
                    "%vaultable%", vaultable
            );
            builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.SCAN_ENTRY_LINE, placeholdersEntry)));
            // Vault button for this entry
            builder.button(Text.of(MessageKeys.SCAN_ENTRY_VAULT_BUTTON, placeholdersEntry), ctx -> {
                // Show a loading menu while we process DB work
                new LoadingMenu(ctx.player(), getParentMenu(), Map.of("%player%", ctx.player().getName())).open();
                Block targetBlock = ctx.player().getWorld().getBlockAt(entryBlock.getX(), entryBlock.getY(), entryBlock.getZ());
                VaultCaptureService capSvc = VaultStoragePlugin.getInstance().getCaptureService();
                
                capSvc.captureDirectAsync(ctx.player(), targetBlock, success -> {
                    // The block stops being a result once it has no Bolt owner: it was vaulted, or
                    // its protection was removed. The cached list is corrected, not rebuilt.
                    BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
                    List<ScanResult> remaining = entries;
                    if (bolt == null || bolt.getOwner(targetBlock) == null) {
                        VaultStoragePlugin.getInstance().getScanService().forget(targetBlock);
                        remaining = entries.stream().filter(result -> !(result.block().getX() == targetBlock.getX()
                                && result.block().getY() == targetBlock.getY()
                                && result.block().getZ() == targetBlock.getZ())).toList();
                    }
                    int newTotal = Math.max(1, (int) Math.ceil(remaining.size() / (double) Math.max(1, RESULTS_PER_PAGE)));
                    int safePage = Math.min(currentPage, newTotal - 1);
                    new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, remaining, safePage).open();
                });
            });
            // Teleport button for this entry (literal coordinates with safe centering)
            builder.button(Text.of(MessageKeys.SCAN_ENTRY_TELEPORT_BUTTON, placeholdersEntry), ctx -> {
                boolean passable = entryBlock.isPassable();
                double destY = passable ? entryBlock.getY() : (entryBlock.getY() + 1);
                Location destination = new Location(ctx.player().getWorld(), entryBlock.getX() + 0.5, destY, entryBlock.getZ() + 0.5, ctx.player().getLocation().getYaw(), ctx.player().getLocation().getPitch());
                ctx.player().teleport(destination);
            });
        }

        // Navigation buttons
        if (currentPage > 0) {
            builder.button(Text.of(MessageKeys.SCAN_PREV_BTN), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, currentPage - 1, filterMode).open());
        }
        if (currentPage < totalPages - 1) {
            builder.button(Text.of(MessageKeys.SCAN_NEXT_BTN), ctx -> new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, currentPage + 1, filterMode).open());
        }

        addFilterButton(builder, currentPage);

        return builder.build();
    }

    private void addFilterButton(AutoDialog.Builder builder, int currentPage) {
        String modeLabel = switch (filterMode) {
            case BLOCK -> Text.plain(MessageKeys.SCAN_FILTER_BLOCK);
            case NON_CONTAINER -> Text.plain(MessageKeys.SCAN_FILTER_NON_CONTAINER);
            case CONTAINER -> Text.plain(MessageKeys.SCAN_FILTER_CONTAINER);
            case ENTITY -> Text.plain(MessageKeys.SCAN_FILTER_ENTITY);
        };
        Map<String,String> phFilter = Map.of("%mode%", modeLabel);
        builder.sizableButton(Text.of(MessageKeys.SCAN_FILTER_BTN, phFilter), ctx -> {
            FilterMode next = filterMode.next();
            new VaultScanMenu(ctx.player(), getParentMenu(), uiContext, regionId, entries, currentPage, next).open();
        }, 100);
    }

    private List<ScanResult> applyTypeFilter(List<ScanResult> source, FilterMode mode) {
        // In ENTITY filter mode, block list is not used; entity protections are rendered separately.
        if (mode == FilterMode.BLOCK || mode == FilterMode.ENTITY) return source;
        List<ScanResult> out = new ArrayList<>();
        boolean wantContainers = (mode == FilterMode.CONTAINER);
        for (ScanResult r : source) {
            boolean isContainer = isContainerMaterial(r.type());
            if (wantContainers && isContainer) {
                out.add(r);
            } else if (!wantContainers && !isContainer) {
                out.add(r);
            }
        }
        return out;
    }

    // This is a faster check than instanceof Container, used for filtering
    private boolean isContainerMaterial(Material type) {
        return switch (type) {
            // Standard storage
            case CHEST, TRAPPED_CHEST, BARREL,
                 SHULKER_BOX, WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX, MAGENTA_SHULKER_BOX,
                 LIGHT_BLUE_SHULKER_BOX, YELLOW_SHULKER_BOX, LIME_SHULKER_BOX, PINK_SHULKER_BOX,
                 GRAY_SHULKER_BOX, LIGHT_GRAY_SHULKER_BOX, CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX,
                 BLUE_SHULKER_BOX, BROWN_SHULKER_BOX, GREEN_SHULKER_BOX, RED_SHULKER_BOX,
                 BLACK_SHULKER_BOX -> true;

            // Machines & Redstone
            case DISPENSER, DROPPER, HOPPER, BREWING_STAND,
                 FURNACE, BLAST_FURNACE, SMOKER,
                 CRAFTER -> true;

            default -> false;
        };
    }
    private @NotNull World getWorld() {
        return getPlayer().getWorld();
    }

    @Override
    public void open() {
        setDialog(build());
        super.open();
    }
}

