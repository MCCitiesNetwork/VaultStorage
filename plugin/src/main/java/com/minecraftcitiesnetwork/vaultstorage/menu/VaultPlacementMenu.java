package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultFilter;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.placement.PlacementRules;
import com.minecraftcitiesnetwork.vaultstorage.placement.PlayerPlaceVaultEvent;
import com.minecraftcitiesnetwork.vaultstorage.placement.VaultPlacementService;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager.Mode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/** Places a vault back: the player starts placement mode and right-clicks where it should go. */
public final class VaultPlacementMenu extends ChildMenuImp {

    private final @NotNull UUID vaultId;
    private final @NotNull VaultUIContext context;
    private final @NotNull Consumer<Player> toVault;
    private final @NotNull Consumer<Player> toList;

    /**
     * @param toVault where Back leads: the vault this placement is for
     * @param toList  where a finished placement leads: the list the vault was picked from
     */
    public VaultPlacementMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull UUID vaultId,
                              @NotNull VaultUIContext context, @NotNull Consumer<Player> toVault,
                              @NotNull Consumer<Player> toList) {
        super(player, parent);
        this.vaultId = vaultId;
        this.context = context;
        this.toVault = toVault;
        this.toList = toList;
    }

    @Override
    protected @NotNull AutoDialog build() {
        Map<String, String> placeholders = Map.of("%player%", player.getName());
        return AutoDialog.builder()
                .title(plugin.text().of(MessageKeys.PLACEMENT_TITLE, placeholders))
                .line(plugin.text().of(MessageKeys.PLACEMENT_INSTRUCTION, placeholders))
                .button(plugin.text().of(MessageKeys.PLACEMENT_START_BTN), this::startPlacement)
                .button(plugin.text().of(MessageKeys.PLACEMENT_BACK_BTN), this.toVault)
                .build();
    }

    /**
     * Puts the player in placement mode: the next right-click on a block places the vault
     * against the clicked face, a left-click cancels. The loading menu shown while the
     * database works is ended on every path.
     */
    private void startPlacement(@NotNull Player actor) {
        VaultSessionManager.Session session = plugin.sessionManager().getOrCreate(actor.getUniqueId());
        // Switch to PLACEMENT mode, cancelling any prior capture/placement state
        session.switchTo(Mode.PLACEMENT);
        final BukkitTask[] actionbarTask = new BukkitTask[1];

        session.dynamicListener().setListener(new Listener() {
            @EventHandler
            public void onInteract(@NotNull PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;

                // Ignore off-hand events to prevent duplicate processing
                if (event.getHand() == EquipmentSlot.OFF_HAND) return;

                // Verify session is still in PLACEMENT mode
                if (session.mode() != Mode.PLACEMENT) return;

                Action action = event.getAction();
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    event.setCancelled(true);
                    session.dynamicListener().stop();
                    if (actionbarTask[0] != null) actionbarTask[0].cancel();
                    session.clearActionBarTask();
                    session.switchTo(Mode.NONE);
                    actor.sendMessage(plugin.text().of(MessageKeys.PLACEMENT_PLACEMENT_CANCELLED));
                    return; // Do NOT reopen menu
                }
                if (action != Action.RIGHT_CLICK_BLOCK) return;
                event.setCancelled(true);
                Block clicked = event.getClickedBlock();
                if (clicked == null) return;
                BlockFace face = event.getBlockFace();
                Block target = clicked.getRelative(face);
                Location targetLoc = target.getLocation();

                // The place permission, and somewhere the player may place: any grant, or the override.
                switch (mayPlaceAt(actor, targetLoc)) {
                    case NO_PERMISSION -> {
                        actor.sendMessage(plugin.text().of(MessageKeys.PLACEMENT_NO_PERMISSION));
                        return;
                    }
                    case NOT_HERE -> {
                        actor.sendMessage(plugin.text().of(MessageKeys.PLACEMENT_NOT_ALLOWED));
                        return; // Stay in placement mode
                    }
                    case ALLOWED -> { }
                }

                VaultPlacementService placement = plugin.placementService();
                new LoadingMenu(actor, parentMenu(), Map.of("%player%", actor.getName(), "%vault%", String.valueOf(vaultId))).open();
                // Stop session listener and actionbar (single placement then exit mode)
                session.dynamicListener().stop();
                if (actionbarTask[0] != null) actionbarTask[0].cancel();
                session.clearActionBarTask();
                session.switchTo(Mode.NONE);

                // The reply comes on the main thread.
                placement.placeFromDatabaseRelativeAsync(vaultId, targetLoc, result -> {
                    if (!actor.isOnline()) {
                        return;
                    }
                    actor.sendMessage(plugin.text().of(result.messageKey()));
                    if (result.success()) {
                        // Fire Vault placed event
                        new PlayerPlaceVaultEvent(actor, targetLoc).callEvent();

                        // Back to the list if there is anything left on it.
                        VaultFilter remaining = new VaultFilter(context.filterOwner(), null);
                        CompletableFuture.supplyAsync(() -> plugin.repository().count(remaining), plugin.databaseExecutor())
                                .thenAcceptAsync(left -> {
                                    if (left > 0) {
                                        toList.accept(actor);
                                    } else {
                                        actor.sendMessage(plugin.text().of(MessageKeys.PLACEMENT_NONE_LEFT));
                                        actor.closeDialog();
                                    }
                                }, plugin.mainThread())
                                .exceptionally(failure -> {
                                    plugin.getLogger().log(Level.WARNING, "Could not count the remaining vaults.", failure);
                                    // The vault is placed; only the way back is unknown. The
                                    // loading dialog must not be left up.
                                    plugin.mainThread()
                                            .execute(actor::closeDialog);
                                    return null;
                                });
                    } else {
                        // Nothing was placed and the vault is still there: back to it, to try again.
                        toVault.accept(actor);
                    }
                });
            }
        });

        // Actionbar updater registered in session for cleanup when switching modes
        actionbarTask[0] = new BukkitRunnable() {
            @Override public void run() {
                if (!actor.isOnline()) {
                    cancel();
                    return;
                }
                Block target = actor.getTargetBlockExact(6);
                if (target == null) {
                    actor.sendActionBar(plugin.text().of(MessageKeys.PLACEMENT_ACTION_BAR_IDLE));
                    return;
                }
                boolean allowed = mayPlaceAt(actor, target.getLocation()) == PlacementRules.Decision.ALLOWED;
                actor.sendActionBar(plugin.text().of(allowed
                        ? MessageKeys.PLACEMENT_ACTION_BAR_PLACEABLE : MessageKeys.PLACEMENT_ACTION_BAR_NOT_PLACEABLE));
            }
        }.runTaskTimer(plugin, 0L, 5L);
        session.setActionBarTask(actionbarTask[0]);

        session.dynamicListener().start();
        actor.closeDialog();
    }

    private PlacementRules.@NotNull Decision mayPlaceAt(@NotNull Player actor, @NotNull Location target) {
        return PlacementRules.decide(actor.hasPermission(Permissions.ACTION_PLACE),
                actor.hasPermission(Permissions.BYPASS_REGION),
                () -> plugin.integrations().mayPlace(actor.getUniqueId(), target));
    }
}
