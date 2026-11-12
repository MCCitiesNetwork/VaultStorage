package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.config.Text;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultFilter;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.placement.PlayerPlaceVaultEvent;
import com.minecraftcitiesnetwork.vaultstorage.menu.AutoDialog;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.placement.VaultPlacementService;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager;
import com.minecraftcitiesnetwork.vaultstorage.session.VaultSessionManager.Mode;
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

import java.util.Map;
import java.util.UUID;

/**
 * Placement UI that mirrors the capture flow but places a selected vault relatively to the clicked face.
 */
public class VaultPlacementMenu extends ChildMenuImp {

    private final UUID vaultId;



    private final VaultUIContext context;
    /** Ensure YAML exists. */

    public VaultPlacementMenu(Player player, ParentMenuImp parent, UUID vaultId, VaultUIContext context) {
        super(player, parent, "vault_place_" + vaultId);
        this.vaultId = vaultId;
        this.context = context;
        setDialog(build());
    }

    private Dialog build() {
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(Text.of(MessageKeys.PLACEMENT_TITLE, Map.of("%player%", getPlayer().getName())));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.PLACEMENT_INSTRUCTION, Map.of("%player%", getPlayer().getName()))));
        builder.button(Text.of(MessageKeys.PLACEMENT_START_BTN), ctx -> startPlacement(ctx.player()));
        builder.button(Text.of(MessageKeys.PLACEMENT_BACK_BTN), ctx -> getParentMenu().open());
        return builder.build();
    }

    @Override
    public void open() {
        setDialog(build());
        super.open();
    }

    /**
     * Starts placement interaction capturing the next right-click on a block face.
     * <p>
     * Concurrency notes:
     * <ul>
     *   <li>Shows a configurable {@link LoadingMenu} while the placement DB operation runs asynchronously.</li>
     *   <li>All UI updates (messages/dialog opens) are scheduled back on the main thread after the DB callback.</li>
     *   <li>Prevents race conditions that could reopen dialogs before the DB result is available.</li>
     * </ul>
     * </p>
     */
    private void startPlacement(Player actor) {
        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId());
        // Switch to PLACEMENT mode, cancelling any prior capture/placement state
        session.switchTo(Mode.PLACEMENT);
        final BukkitTask[] actionbarTask = new BukkitTask[1];

        session.getDynamicListener().setListener(new Listener() {
            @EventHandler
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;

                // Ignore off-hand events to prevent duplicate processing
                if (event.getHand() == EquipmentSlot.OFF_HAND) return;

                // Verify session is still in PLACEMENT mode
                if (session.getMode() != Mode.PLACEMENT) return;

                Action action = event.getAction();
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    event.setCancelled(true);
                    session.getDynamicListener().stop();
                    if (actionbarTask[0] != null) actionbarTask[0].cancel();
                    session.clearActionBarTask();
                    session.switchTo(Mode.NONE);
                    actor.sendMessage(Text.of(MessageKeys.PLACEMENT_PLACEMENT_CANCELLED));
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
                if (!actor.hasPermission(Permissions.ACTION_PLACE)) {
                    actor.sendMessage(Text.of(MessageKeys.PLACEMENT_NO_PERMISSION));
                    return;
                }
                boolean allowed = actor.hasPermission(Permissions.BYPASS_REGION)
                        || VaultStoragePlugin.getInstance().integrations().mayPlace(actor.getUniqueId(), targetLoc);
                if (!allowed) {
                    actor.sendMessage(Text.of(MessageKeys.PLACEMENT_NOT_ALLOWED));
                    return; // Stay in placement mode
                }

                VaultPlacementService placement = VaultStoragePlugin.getInstance().getPlacementService();
                new LoadingMenu(actor, getParentMenu(), Map.of("%player%", actor.getName(), "%vault%", String.valueOf(vaultId))).open();
                // Stop session listener and actionbar (single placement then exit mode)
                session.getDynamicListener().stop();
                if (actionbarTask[0] != null) actionbarTask[0].cancel();
                session.clearActionBarTask();
                session.switchTo(Mode.NONE);

                placement.placeFromDatabaseRelativeAsync(vaultId, targetLoc, result -> {
                    var plugin = VaultStoragePlugin.getInstance();
                    new BukkitRunnable() {
                        @Override public void run() {
                            actor.sendMessage(Text.of(result.messageKey()));
                            if (result.success()) {
                                // Fire Vault placed event
                                new PlayerPlaceVaultEvent(actor, targetLoc).callEvent();

                                // Back to the list if there is anything left on it.
                                VaultFilter remaining = new VaultFilter(context.filterOwner(), null);
                                CompletableFuture.supplyAsync(() -> plugin.repository().count(remaining), plugin.databaseExecutor())
                                        .thenAcceptAsync(left -> {
                                            if (left > 0) {
                                                new VaultListMenu(actor, (ParentMenuImp) getParentMenu(), context, "").open();
                                            } else {
                                                actor.sendMessage(Text.of(MessageKeys.PLACEMENT_NONE_LEFT));
                                                actor.closeDialog();
                                            }
                                        }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
                                        .exceptionally(failure -> {
                                            plugin.getLogger().log(Level.WARNING, "Could not count the remaining vaults.", failure);
                                            return null;
                                        });
                            } else {
                                // Failure: return to list for retry
                                new VaultListMenu(actor, (ParentMenuImp) getParentMenu(), context, "").open();
                            }
                        }
                    }.runTask(plugin);
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
                    actor.sendActionBar(Text.of(MessageKeys.PLACEMENT_ACTION_BAR_IDLE));
                    return;
                }
                boolean allowed = actor.hasPermission(Permissions.ACTION_PLACE)
                        && (actor.hasPermission(Permissions.BYPASS_REGION)
                        || VaultStoragePlugin.getInstance().integrations().mayPlace(actor.getUniqueId(), target.getLocation()));
                actor.sendActionBar(Text.of(allowed
                        ? MessageKeys.PLACEMENT_ACTION_BAR_PLACEABLE : MessageKeys.PLACEMENT_ACTION_BAR_NOT_PLACEABLE));
            }
        }.runTaskTimer(VaultStoragePlugin.getInstance(), 0L, 5L);
        session.setActionBarTask(actionbarTask[0]);

        session.getDynamicListener().start();
        actor.closeDialog();
    }
}
