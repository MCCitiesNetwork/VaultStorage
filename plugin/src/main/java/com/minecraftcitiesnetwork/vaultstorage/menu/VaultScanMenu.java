package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.region.VaultRegion;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Where a scan starts: search the world's regions by name, or scan the region the player stands in. */
public final class VaultScanMenu extends ChildMenuImp {

    private static final String KEY_QUERY = "QUERY";
    private static final int QUERY_LENGTH = 64;

    private final @NotNull VaultUIContext uiContext;

    public VaultScanMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx) {
        super(player, parent);
        this.uiContext = ctx;
    }

    @Override
    protected @NotNull AutoDialog build() {
        return AutoDialog.builder()
                .title(plugin.text().of(MessageKeys.SCAN_TITLE, Map.of("%player%", player.getName())))
                .line(plugin.text().of(MessageKeys.SCAN_SEARCH_LABEL))
                .textInput(KEY_QUERY, plugin.text().of(MessageKeys.SCAN_SEARCH_LABEL), QUERY_LENGTH)
                .submitButton(plugin.text().of(MessageKeys.SCAN_SEARCH_BTN), (actor, answers) ->
                        VaultRegionListMenu.search(actor, parentMenu(), uiContext,
                                answers.getOrDefault(KEY_QUERY, "")).open())
                .button(plugin.text().of(MessageKeys.SCAN_HERE_BTN), this::scanHere)
                .build();
    }

    /** The region the player stands in is scanned; where several overlap, the player picks one. */
    private void scanHere(@NotNull Player actor) {
        // By the regions' real shapes, not their bounding boxes. Read as an entity's location:
        // Player also inherits OfflinePlayer's, which may be null.
        List<VaultRegion> here = plugin.worldGuard().getRegionsAt(((Entity) actor).getLocation().getBlock());
        if (here.isEmpty()) {
            actor.sendMessage(plugin.text().of(MessageKeys.SCAN_NO_REGION_HERE));
        } else if (here.size() == 1) {
            RegionScans.open(parentMenu(), actor, here.getFirst().id(), uiContext);
        } else {
            VaultRegionListMenu.of(actor, parentMenu(), uiContext, here.stream().map(VaultRegion::id).toList()).open();
        }
    }
}
