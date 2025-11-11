package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.service.VaultPlacementService;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.session.VaultSessionManager.Mode;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;

/**
 * Placement UI that mirrors the capture flow but places a selected vault relatively to the clicked face.
 */
public class VaultPlacementMenu extends ChildMenuImp {

    private final UUID vaultId;

    /** Configurable menu texts for placement. */
    public static class Config implements Dto {
        /** Dialog title. Supports %player%. */
        public String title = "<gold><bold>Vault Placement</bold></gold>";
        /** Instruction line explaining how to start placement. Supports %player%. */
        public String instruction = "<gray>Right-click a block face to place the vault adjacently. Left-click to cancel.</gray>";
        /** Button to start placement mode. */
        public String startBtn = "<yellow><bold>Start Placement</bold></yellow>";
        /** Button to go back. */
        public String backBtn = "<red><bold>Back</bold></red>";
        /** Chat message when placement is cancelled. */
        public String placementCancelled = "Placement cancelled.";
        /** Actionbar while not looking at a valid target. */
        public String actionBarIdle = "<yellow>Placement mode</yellow> - Right-click a block face. <gray>Left-click to cancel.</gray>";
        /** Actionbar when looking at a block. Placeholder %placeable% yes/no */
        public String actionBarTarget = "<gray>Placeable:</gray> <white>%placeable%</white>";
        /** Text shown when allowed to place. */
        public String placeYes = "yes";
        /** Text shown when NOT allowed to place. */
        public String placeNo = "no";
        /** Message when player lacks membership and no override permission. */
        public String notAllowed = "<red>You must be a region member/owner or have override permission.</red>";
        /** Success prefix for placement (placeholder %msg%). */
        public String placeOk = "<green>%msg%</green>";
        /** Failure prefix for placement (placeholder %msg%). */
        public String placeFail = "<red>%msg%</red>";
        /** Loading message shown while placement completes. */
        public String loading = "<gray>Processing placement, please wait...</gray>";
    }

    private static final String HEADER = String.join("\n",
            "VaultPlacementMenu configuration.",
            "Fields accept MiniMessage or plain strings.",
            "Placeholders:",
            "- %player% -> actor/player name",
            "- %placeable% -> yes/no",
            "- %msg% -> placement result"
    );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultPlacementMenu", DataFolder.MENUS, HEADER);
    private final VaultUIContext context;
    private static Config cfg() { return YML.loadOrCreate(Config::new); }
    /** Ensure YAML exists. */
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }

    public VaultPlacementMenu(Player player, ParentMenuImp parent, UUID vaultId, VaultUIContext context) {
        super(player, parent, "vault_place_" + vaultId);
        this.vaultId = vaultId;
        this.context = context;
        setDialog(build());
    }

    private Dialog build() {
        Config c = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(MiniMessageUtil.parseOrPlain(c.title, Map.of("%player%", getPlayer().getName())));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(c.instruction, Map.of("%player%", getPlayer().getName()))));
        builder.button(MiniMessageUtil.parseOrPlain(c.startBtn), ctx -> startPlacement(ctx.player()));
        builder.button(MiniMessageUtil.parseOrPlain(c.backBtn), ctx -> getParentMenu().open());
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
        Config config = cfg();
        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId());
        // Switch to PLACEMENT mode, cancelling any prior capture/placement state
        session.switchTo(Mode.PLACEMENT);
        final org.bukkit.scheduler.BukkitTask[] actionbarTask = new org.bukkit.scheduler.BukkitTask[1];

        session.getDynamicListener().setListener(new Listener() {
            @EventHandler
            public void onInteract(PlayerInteractEvent event) {
                if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;
                Action action = event.getAction();
                event.setCancelled(true);
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    session.getDynamicListener().stop();
                    if (actionbarTask[0] != null) actionbarTask[0].cancel();
                    session.clearActionBarTask();
                    session.switchTo(Mode.NONE);
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(config.placementCancelled));
                    return; // Do NOT reopen menu
                }
                if (action != Action.RIGHT_CLICK_BLOCK) return;
                Block clicked = event.getClickedBlock();
                if (clicked == null) return;
                BlockFace face = event.getBlockFace();
                Block target = clicked.getRelative(face);
                Location targetLoc = target.getLocation();

                // Membership/override check
                WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();
                boolean allowed = true;
                if (wgs != null) {
                    var regs = wgs.getRegionsAt(target);
                    UUID viewer = actor.getUniqueId();
                    boolean isParticipant = regs.stream().anyMatch(r -> r.isPartOfRegion(viewer));
                    boolean hasOverride = VaultPermission.ACTION_PLACE_OVERRIDE.has(actor);
                    allowed = isParticipant || hasOverride;
                }
                if (!allowed) {
                    actor.sendMessage(MiniMessageUtil.parseOrPlain(config.notAllowed));
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
                    new BukkitRunnable() { @Override public void run() {
                        Map<String,String> ph = java.util.Map.of("%msg%", result.message());
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(result.success() ? config.placeOk : config.placeFail, ph));
                        if (result.success()) {
                            // Re-query vaults synchronously to determine if any remain
                            try {
                                var vs = plugin.getVaultService();
                                UUID worldId = actor.getWorld().getUID();
                                boolean anyLeft;
                                if (context.filterOwner() != null) {
                                    var owned = vs.listByOwner(context.filterOwner());
                                    anyLeft = owned.stream().anyMatch(v -> worldId.equals(v.worldUuid));
                                } else {
                                    anyLeft = !vs.listInWorld(worldId).isEmpty();
                                }
                                if (anyLeft) {
                                    new VaultListMenu(actor, (ParentMenuImp) getParentMenu(), context, "").open();
                                } else {
                                    actor.sendMessage("You have no more vaults to place in this world.");
                                    actor.closeDialog();
                                }
                            } catch (Throwable t) {
                                // On error, fallback to list menu for recovery
                                new VaultListMenu(actor, (ParentMenuImp) getParentMenu(), context, "").open();
                            }
                        } else {
                            // Failure: return to list for retry
                            new VaultListMenu(actor, (ParentMenuImp) getParentMenu(), context, "").open();
                        }
                    }}.runTask(plugin);
                });
            }
        });

        // Actionbar updater registered in session for cleanup when switching modes
        actionbarTask[0] = new BukkitRunnable() {
            @Override public void run() {
                if (!actor.isOnline()) { cancel(); return; }
                Block target = actor.getTargetBlockExact(6);
                if (target == null) { actor.sendActionBar(MiniMessageUtil.parseOrPlain(config.actionBarIdle)); return; }
                WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();
                boolean isParticipant = false;
                if (wgs != null) {
                    var regs = wgs.getRegionsAt(target);
                    UUID viewer = actor.getUniqueId();
                    isParticipant = regs.stream().anyMatch(r -> r.isPartOfRegion(viewer));
                }
                boolean hasOverride = VaultPermission.ACTION_PLACE_OVERRIDE.has(actor);
                boolean allowed = isParticipant || hasOverride;
                var ph = Map.of("%placeable%", allowed ? config.placeYes : config.placeNo);
                actor.sendActionBar(MiniMessageUtil.parseOrPlain(config.actionBarTarget, ph));
            }
        }.runTaskTimer(VaultStoragePlugin.getInstance(), 0L, 5L);
        session.setActionBarTask(actionbarTask[0]);

        session.getDynamicListener().start();
        actor.closeDialog();
    }
}
