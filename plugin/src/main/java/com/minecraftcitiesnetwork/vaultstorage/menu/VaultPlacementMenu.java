package com.minecraftcitiesnetwork.vaultstorage.menu;

import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultFilter;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
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
public final class VaultPlacementMenu extends ChildMenuImp {

    private final UUID vaultId;



    private final VaultUIContext context;
    private final Consumer<Player> toVault;
    private final Consumer<Player> toList;

    /**
     * @param toVault where Back leads: the vault this placement is for
     * @param toList  where a finished placement leads: the list the vault was picked from
     */
    public VaultPlacementMenu(@NotNull Player player, @NotNull ParentMenuImp parent, @NotNull UUID vaultId,
                              @NotNull VaultUIContext context, @NotNull Consumer<Player> toVault,
                              @NotNull Consumer<Player> toList) {
        super(player, parent, "vault_place_" + vaultId);
        this.vaultId = vaultId;
        this.context = context;
        this.toVault = toVault;
        this.toList = toList;
        setDialog(build());
    }

    private @NotNull Dialog build() {
        AutoDialog.Builder builder = dialogBuilder();
        builder.title(plugin.text().of(MessageKeys.PLACEMENT_TITLE, Map.of("%player%", player().getName())));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addBody(DialogBody.plainMessage(plugin.text().of(MessageKeys.PLACEMENT_INSTRUCTION, Map.of("%player%", player().getName()))));
        builder.button(plugin.text().of(MessageKeys.PLACEMENT_START_BTN), ctx -> startPlacement(ctx.player()));
        builder.button(plugin.text().of(MessageKeys.PLACEMENT_BACK_BTN), ctx -> this.toVault.accept(ctx.player()));
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
                if (!actor.hasPermission(Permissions.ACTION_PLACE)) {
                    actor.sendMessage(plugin.text().of(MessageKeys.PLACEMENT_NO_PERMISSION));
                    return;
                }
                boolean allowed = actor.hasPermission(Permissions.BYPASS_REGION)
                        || plugin.integrations().mayPlace(actor.getUniqueId(), targetLoc);
                if (!allowed) {
                    actor.sendMessage(plugin.text().of(MessageKeys.PLACEMENT_NOT_ALLOWED));
                    return; // Stay in placement mode
                }

                VaultPlacementService placement = plugin.placementService();
                new LoadingMenu(actor, parentMenu(), Map.of("%player%", actor.getName(), "%vault%", String.valueOf(vaultId))).open();
                // Stop session listener and actionbar (single placement then exit mode)
                session.dynamicListener().stop();
                if (actionbarTask[0] != null) actionbarTask[0].cancel();
                session.clearActionBarTask();
                session.switchTo(Mode.NONE);

                placement.placeFromDatabaseRelativeAsync(vaultId, targetLoc, result -> {
                    new BukkitRunnable() {
                        @Override public void run() {
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
                                        }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin))
                                        .exceptionally(failure -> {
                                            plugin.getLogger().log(Level.WARNING, "Could not count the remaining vaults.", failure);
                                            // The vault is placed; only the way back is unknown. The
                                            // loading dialog must not be left up.
                                            plugin.getServer().getScheduler().getMainThreadExecutor(plugin)
                                                    .execute(actor::closeDialog);
                                            return null;
                                        });
                            } else {
                                // Nothing was placed and the vault is still there: back to it, to try again.
                                toVault.accept(actor);
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
                    actor.sendActionBar(plugin.text().of(MessageKeys.PLACEMENT_ACTION_BAR_IDLE));
                    return;
                }
                boolean allowed = actor.hasPermission(Permissions.ACTION_PLACE)
                        && (actor.hasPermission(Permissions.BYPASS_REGION)
                        || plugin.integrations().mayPlace(actor.getUniqueId(), target.getLocation()));
                actor.sendActionBar(plugin.text().of(allowed
                        ? MessageKeys.PLACEMENT_ACTION_BAR_PLACEABLE : MessageKeys.PLACEMENT_ACTION_BAR_NOT_PLACEABLE));
            }
        }.runTaskTimer(plugin, 0L, 5L);
        session.setActionBarTask(actionbarTask[0]);

        session.dynamicListener().start();
        actor.closeDialog();
    }
}
