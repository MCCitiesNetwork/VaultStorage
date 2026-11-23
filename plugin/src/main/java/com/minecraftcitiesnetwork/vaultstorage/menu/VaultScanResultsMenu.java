package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import com.minecraftcitiesnetwork.vaultstorage.scan.ScanResult;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.popcraft.bolt.protection.EntityProtection;

/**
 * What a scan of one region found, a page at a time: the locked blocks the player may vault,
 * each with a button that vaults it, or the region's locked entities. A block that is not a
 * container, or a container that is empty, only loses its lock.
 */
public final class VaultScanResultsMenu extends ChildMenuImp {

    /** Which of the results are listed. */
    public enum Filter {
        BLOCK(MessageKeys.SCAN_FILTER_BTN_BLOCK),
        NON_CONTAINER(MessageKeys.SCAN_FILTER_BTN_NON_CONTAINER),
        CONTAINER(MessageKeys.SCAN_FILTER_BTN_CONTAINER),
        ENTITY(MessageKeys.SCAN_FILTER_BTN_ENTITY);

        private final String buttonKey;

        Filter(@NotNull String buttonKey) {
            this.buttonKey = buttonKey;
        }

        private @NotNull Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** Entities are not among the scan's results: they are read when their page is built. */
        private boolean shows(@NotNull ScanResult result) {
            return switch (this) {
                case BLOCK, ENTITY -> true;
                case CONTAINER -> isContainer(result.type());
                case NON_CONTAINER -> !isContainer(result.type());
            };
        }
    }

    /** Few enough for a dialog: each result is a line and up to two buttons. */
    private static final int PER_PAGE = 5;
    private static final int NARROW = 100;

    private final @NotNull VaultUIContext uiContext;
    private final @NotNull String regionId;
    private final @NotNull List<ScanResult> entries;
    private final int page;
    private final @NotNull Filter filter;

    public VaultScanResultsMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx,
                                @NotNull String regionId, @NotNull List<ScanResult> entries, int page,
                                @NotNull Filter filter) {
        super(player, parent);
        this.uiContext = ctx;
        this.regionId = regionId;
        this.entries = entries;
        this.page = page;
        this.filter = filter;
    }

    private @NotNull VaultScanResultsMenu with(@NotNull Player actor, @NotNull List<ScanResult> results,
                                               int otherPage, @NotNull Filter otherFilter) {
        return new VaultScanResultsMenu(actor, parentMenu(), this.uiContext, this.regionId, results, otherPage,
                otherFilter);
    }

    @Override
    protected @NotNull AutoDialog build() {
        AutoDialog.Builder builder = AutoDialog.builder()
                .title(plugin.text().of(MessageKeys.SCAN_TITLE, Map.of("%player%", player.getName())));
        List<ScanResult> shown = this.entries.stream().filter(this.filter::shows).toList();
        builder.line(plugin.text().of(MessageKeys.SCAN_RESULTS_HEADER,
                Map.of("%region%", this.regionId, "%count%", String.valueOf(shown.size()))));
        Paging paging = this.filter == Filter.ENTITY ? entities(builder) : blocks(builder, shown);
        if (paging != null && paging.hasPrevious()) {
            builder.button(plugin.text().of(MessageKeys.SCAN_PREV_BTN),
                    actor -> with(actor, this.entries, paging.page() - 1, this.filter).open());
        }
        if (paging != null && paging.hasNext()) {
            builder.button(plugin.text().of(MessageKeys.SCAN_NEXT_BTN),
                    actor -> with(actor, this.entries, paging.page() + 1, this.filter).open());
        }
        int shownPage = paging == null ? 0 : paging.page();
        builder.wideButton(plugin.text().of(this.filter.buttonKey), NARROW,
                actor -> with(actor, this.entries, shownPage, this.filter.next()).open());
        return builder.build();
    }

    /** Adds this page of blocks. Null when there are none. */
    private @Nullable Paging blocks(AutoDialog.@NotNull Builder builder, @NotNull List<ScanResult> shown) {
        if (shown.isEmpty()) {
            builder.line(plugin.text().of(MessageKeys.SCAN_NONE_FOUND));
            return null;
        }
        Paging paging = Paging.of(shown.size(), PER_PAGE, this.page);
        builder.line(plugin.text().of(MessageKeys.SCAN_PAGE_LABEL, Map.of(
                "%page%", String.valueOf(paging.page() + 1), "%total%", String.valueOf(paging.pages()))));
        for (int index = paging.offset(); index < Math.min(shown.size(), paging.offset() + PER_PAGE); index++) {
            ScanResult entry = shown.get(index);
            Block block = entry.block();
            String kind = plugin.text().plain(isContainer(entry.type())
                    ? MessageKeys.SCAN_KIND_CONTAINER : MessageKeys.SCAN_KIND_BLOCK);
            Map<String, String> placeholders = placeholders(block.getX(), block.getY(), block.getZ(), index, kind,
                    entry.owner());
            // A scan lists only what the player may vault.
            builder.line(plugin.text().of(MessageKeys.SCAN_ENTRY_VAULTABLE, placeholders));
            builder.button(plugin.text().of(MessageKeys.SCAN_ENTRY_VAULT_BUTTON, placeholders),
                    actor -> vault(actor, block, paging.page()));
            permissionButton(builder, Permissions.SCAN_TELEPORT,
                    plugin.text().of(MessageKeys.SCAN_ENTRY_TELEPORT_BUTTON, placeholders),
                    actor -> teleport(actor, block));
        }
        return paging;
    }

    /** Vaults a listed block, then shows the list without it. captureDirectAsync checks the permission. */
    private void vault(@NotNull Player actor, @NotNull Block listed, int currentPage) {
        new LoadingMenu(actor, parentMenu(), Map.of("%player%", actor.getName())).open();
        Block target = actor.getWorld().getBlockAt(listed.getX(), listed.getY(), listed.getZ());
        plugin.captureService().captureDirectAsync(actor, target, success -> {
            // The block stops being a result once it has no Bolt owner: it was vaulted, or its
            // lock was removed. The lists are corrected, not scanned for again.
            List<ScanResult> remaining = this.entries;
            if (plugin.bolt().getOwner(target) == null) {
                plugin.scanService().forget(target);
                remaining = this.entries.stream().filter(result -> !(result.block().getX() == target.getX()
                        && result.block().getY() == target.getY()
                        && result.block().getZ() == target.getZ())).toList();
            }
            with(actor, remaining, currentPage, Filter.BLOCK).open();
        });
    }

    /** Onto the block, or into it when it can be stood in. */
    private static void teleport(@NotNull Player actor, @NotNull Block block) {
        Location facing = ((Entity) actor).getLocation();
        actor.teleport(new Location(actor.getWorld(), block.getX() + 0.5,
                block.isPassable() ? block.getY() : block.getY() + 1, block.getZ() + 0.5,
                facing.getYaw(), facing.getPitch()));
    }

    /** Adds this page of the region's locked entities. Null when there are none. */
    private @Nullable Paging entities(AutoDialog.@NotNull Builder builder) {
        VaultRegion region = plugin.worldGuard().getRegionById(this.regionId, player.getWorld());
        if (region == null) {
            builder.line(plugin.text().of(MessageKeys.SCAN_REGION_NOT_FOUND, Map.of("%region%", this.regionId)));
            return null;
        }
        // Bolt is asked by bounding box; the region's shape decides what is in it. A fixed
        // order, so a page shows the same entities each time it is built.
        List<Map.Entry<EntityProtection, Entity>> locked = plugin.bolt()
                .getProtectedEntities(player.getWorld(), region.boundingBox()).entrySet().stream()
                .filter(entry -> plugin.worldGuard().isInside(this.regionId, entry.getValue().getLocation().getBlock()))
                .sorted(Comparator.<Map.Entry<EntityProtection, Entity>>comparingInt(
                                entry -> entry.getValue().getLocation().getBlockX())
                        .thenComparingInt(entry -> entry.getValue().getLocation().getBlockY())
                        .thenComparingInt(entry -> entry.getValue().getLocation().getBlockZ())
                        .thenComparing(entry -> entry.getValue().getUniqueId()))
                .toList();
        if (locked.isEmpty()) {
            builder.line(plugin.text().of(MessageKeys.SCAN_NO_ENTITIES));
            return null;
        }
        Paging paging = Paging.of(locked.size(), PER_PAGE, this.page);
        for (int index = paging.offset(); index < Math.min(locked.size(), paging.offset() + PER_PAGE); index++) {
            EntityProtection protection = locked.get(index).getKey();
            Entity entity = locked.get(index).getValue();
            Location at = entity.getLocation();
            Map<String, String> placeholders = placeholders(at.getBlockX(), at.getBlockY(), at.getBlockZ(), index,
                    VaultLabel.materialName(entity.getType().name()), protection.getOwner());
            builder.line(plugin.text().of(plugin.capturePolicy().evaluate(player, entity).allowed()
                    ? MessageKeys.SCAN_ENTRY_VAULTABLE : MessageKeys.SCAN_ENTRY_NOT_VAULTABLE, placeholders));
            builder.wideButton(plugin.text().of(MessageKeys.SCAN_ENTRY_VAULT_BUTTON, placeholders), NARROW,
                    actor -> vault(actor, protection, entity, paging.page()));
        }
        return paging;
    }

    /**
     * An item frame or painting is vaulted with what it shows. Any other locked entity holds
     * nothing to store, so it only loses its lock.
     */
    private void vault(@NotNull Player actor, @NotNull EntityProtection protection, @NotNull Entity entity,
                       int currentPage) {
        if (!plugin.capturePolicy().evaluate(actor, entity).allowed()) {
            actor.sendMessage(plugin.text().of(MessageKeys.SCAN_NOT_ALLOWED));
            return;
        }
        Runnable refresh = () -> with(actor, this.entries, currentPage, Filter.ENTITY).open();
        if (entity instanceof Hanging hanging && hanging.isValid()) {
            plugin.captureService().captureHanging(actor, hanging).thenRun(refresh);
            return;
        }
        plugin.bolt().removeProtection(protection);
        refresh.run();
    }

    private @NotNull Map<String, String> placeholders(int x, int y, int z, int index, @NotNull String kind,
                                                      @Nullable UUID owner) {
        return Map.of("%x%", String.valueOf(x), "%y%", String.valueOf(y), "%z%", String.valueOf(z),
                "%index%", String.valueOf(index + 1), "%kind%", kind,
                // The scan looked its owners' names up, so a block's is remembered by now.
                "%owner%", owner == null
                        ? plugin.text().plain(MessageKeys.SCAN_OWNER_UNKNOWN) : plugin.names().displayNameNow(owner));
    }

    /** By material, so a result need not have its block state read. */
    private static boolean isContainer(@NotNull Material type) {
        return Tag.SHULKER_BOXES.isTagged(type) || switch (type) {
            case CHEST, TRAPPED_CHEST, BARREL, DISPENSER, DROPPER, HOPPER, BREWING_STAND, FURNACE, BLAST_FURNACE,
                 SMOKER, CRAFTER -> true;
            default -> false;
        };
    }
}
