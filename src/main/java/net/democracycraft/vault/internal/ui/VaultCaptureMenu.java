package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.util.*;

/**
 * Vault capture UI shown when the command is executed.
 * <p>
 * Authorization policy (overlapping-region aware):
 * <ul>
 *   <li>Override permission: may always capture.</li>
 *   <li>Region owner: may capture only if the container has a Bolt owner different from themselves AND that owner is not a member nor an owner of ANY overlapping region.</li>
 *   <li>Region member (non-owner): may not capture any container in overlapping regions.</li>
 *   <li>Non-involved (not owner or member of any overlapping region): may capture only their own Bolt-owned containers.</li>
 *   <li>Unprotected containers (no Bolt owner) are capturable only with override.</li>
 * </ul>
 * Action bar reflects vaultability in real time using the same logic.
 */
public class VaultCaptureMenu extends ParentMenuImp {

    /** Configurable menu texts. */
    public static class Config implements Dto, java.io.Serializable {
        /** Dialog title. Supports %player% placeholder. */
        public String title = "<gold><bold>Vault Capture</bold></gold>";
        /** Instruction line explaining how to start capture. Supports %player%. */
        public String instruction = "<gray>Click any container block to convert it into a Vault. The block will be removed and its items captured.</gray>";
        /** Instruction line explaining how to cancel. Supports %player%. */
        public String cancelHint = "<gray>Left-click anywhere to cancel.</gray>";
        /** Button to start capture mode. Supports %player%. */
        public String startBtn = "<green><bold>Start Capture</bold></green>";
        /** Button to browse vaults. Supports %player%. */
        public String browseBtn = "<green>Browse Vaults</green>";
        /** Button to close dialog. Supports %player%. */
        public String closeBtn = "<red><bold>Close</bold></red>";
        /** Chat message when capture is cancelled. Supports %player%. */
        public String captureCancelled = "Capture cancelled.";
        /** Chat message when target is not a container. Supports %player%. */
        public String notAContainer = "That block is not a container.";
        /** Chat message when capture succeeds. Supports %player%. */
        public String capturedOk = "Vault captured.";
        /** Button to open the scan menu. */
        public String openScanBtn = "<yellow>Scan Region</yellow>";
        /** Actionbar while in capture mode (when not looking at a container). */
        public String actionBarIdle = "<yellow>Capture mode</yellow> - Right-click a container. <gray>Left-click to cancel.</gray>";
        /** Actionbar when looking at a container. Placeholders: %owner% %vaultable% */
        public String actionBarContainer = "<gray>Owner:</gray> <white>%owner%</white> <gray>| Vaultable:</gray> <white>%vaultable%</white>";
        /** Text used when the container has no Bolt protection. */
        public String actionBarUnprotectedOwner = "unprotected";
        /** Text shown when a container is vaultable. */
        public String actionBarVaultableYes = "yes";
        /** Text shown when a container is NOT vaultable. */
        public String actionBarVaultableNo = "no";
        /** Message when player lacks membership and no override permission. */
        public String notAllowed = "<red>Not allowed: region/container rules.</red>";
        /** Message when Bolt has no owner for the target and the player will become the vault owner. */
        public String noBoltOwner = "<yellow>No Bolt owner found; you will be set as the vault owner.</yellow>";
    }

    private static final String HEADER = String.join("\n",
            "VaultCaptureMenu configuration.",
            "Fields accept MiniMessage or plain strings.",
            "Placeholders available:",
            "- %player% -> actor/player name",
            "- %region% -> region id",
            "- %count% -> result count",
            "- %x% %y% %z% %owner% -> per-entry values"
    );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultCaptureMenu", DataFolder.MENUS, HEADER);
    private static Config cfg() { return YML.loadOrCreate(Config::new); }
    /** Ensures the YAML file for this menu exists by creating defaults if missing. */
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }

    /**
     * Creates a new capture menu for the given player.
     * @param player the player who will interact with the dialog
     */
    public VaultCaptureMenu(Player player) {
        super(player, "vault_capture");
        setDialog(build());
    }

    /**
     * Builds the dialog structure and associated actions.
     * @return the constructed dialog instance
     */
    private Dialog build() {
        Config cfg = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> phSelf = Map.of("%player%", getPlayer().getName());
        builder.title(MiniMessageUtil.parseOrPlain(cfg.title, phSelf));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.instruction, phSelf)));
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.cancelHint, phSelf)));

        // Start capture mode: installs a dynamic listener for the next relevant click, then closes the dialog
        builder.button(MiniMessageUtil.parseOrPlain(cfg.startBtn, phSelf), ctx -> {
            Player actor = ctx.player();
            // Require capture permission before entering capture mode
            if (!VaultPermission.ACTION_CAPTURE.has(actor)) {
                actor.sendMessage("You don't have permission to capture containers.");
                return;
            }
            VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId());
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
                        event.getPlayer().sendMessage(MiniMessageUtil.parseOrPlain(cfg.captureCancelled, Map.of("%player%", event.getPlayer().getName())));
                        new VaultCaptureMenu(actor).open();
                        return;
                    }
                    if (action != Action.RIGHT_CLICK_BLOCK) return;
                    Block block = event.getClickedBlock();
                    if (block == null) return;
                    session.getDynamicListener().stop();
                    if (actionbarTask[0] != null) actionbarTask[0].cancel();
                    if (!(block.getState() instanceof Container)) {
                        event.getPlayer().sendMessage(MiniMessageUtil.parseOrPlain(cfg.notAContainer, Map.of("%player%", event.getPlayer().getName())));
                        new VaultCaptureMenu(actor).open();
                        return;
                    }

                    boolean hasOverride = VaultPermission.ACTION_PLACE_OVERRIDE.has(actor);
                    BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
                    UUID originalOwner = null;
                    if (bolt != null) {
                        try { originalOwner = bolt.getOwner(block); } catch (Throwable ignored) {}
                    }
                    boolean playerIsContainerOwner = originalOwner != null && originalOwner.equals(actor.getUniqueId());

                    WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();
                    boolean playerIsRegionOwnerAny = false;
                    boolean playerIsRegionMemberAny = false;
                    boolean containerOwnerIsRegionMemberOrOwnerAny = false;
                    if (wgs != null) {
                        BoundingBox point = new BoundingBox(block.getX(), block.getY(), block.getZ(), block.getX(), block.getY(), block.getZ());
                        var regs = wgs.getRegionsAt(point, block.getWorld());
                        UUID viewer = actor.getUniqueId();
                        for (var r : regs) {
                            if (r.isOwner(viewer)) playerIsRegionOwnerAny = true;
                            if (r.isMember(viewer)) playerIsRegionMemberAny = true;
                            if (originalOwner != null && (r.isOwner(originalOwner) || r.isMember(originalOwner))) {
                                containerOwnerIsRegionMemberOrOwnerAny = true;
                            }
                        }
                    }

                    boolean disallowedOwnerSelf = playerIsRegionOwnerAny && playerIsContainerOwner;

                    boolean baseAllowed;
                    if (playerIsRegionOwnerAny) {
                        baseAllowed = (originalOwner != null) && !playerIsContainerOwner && !containerOwnerIsRegionMemberOrOwnerAny;
                    } else if (playerIsRegionMemberAny) {
                        baseAllowed = false;
                    } else {
                        baseAllowed = playerIsContainerOwner;
                    }

                    if (originalOwner == null && !hasOverride) baseAllowed = false;

                    boolean allowed = !disallowedOwnerSelf && (hasOverride ? true : baseAllowed);

                    try {
                        VaultStoragePlugin.getInstance().getLogger().info(String.format(
                                "[CaptureMenuCheck] actor=%s loc=%d,%d,%d ownerAny=%s memberAny=%s contOwner=%s contOwnerMemberOrOwnerAny=%s isContOwner=%s override=%s disallowedOwnerSelf=%s baseAllowed=%s finalAllowed=%s",
                                actor.getName(), block.getX(), block.getY(), block.getZ(), playerIsRegionOwnerAny, playerIsRegionMemberAny, originalOwner, containerOwnerIsRegionMemberOrOwnerAny, playerIsContainerOwner, hasOverride, disallowedOwnerSelf, baseAllowed, allowed));
                    } catch (Throwable ignored) {}

                    if (!allowed) {
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.notAllowed, Map.of("%player%", actor.getName())));
                        new VaultCaptureMenu(actor).open();
                        return;
                    }

                    if (bolt != null && originalOwner == null && hasOverride) {
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.noBoltOwner));
                    }
                    if (bolt != null) {
                        try { bolt.removeProtection(block); } catch (Throwable ignored) {}
                    }

                    VaultCaptureService captureService = VaultStoragePlugin.getInstance().getCaptureService();
                    VaultImp vault = captureService.captureFromBlock(actor, block);
                    var plugin = VaultStoragePlugin.getInstance();
                    UUID finalOwner = originalOwner != null ? originalOwner : actor.getUniqueId();
                    new BukkitRunnable() {
                        @Override public void run() {
                            var vs = plugin.getVaultService();
                            UUID worldId = block.getWorld().getUID();
                            var existing = vs.findByLocation(worldId, block.getX(), block.getY(), block.getZ());
                            if (existing != null) {
                                vs.delete(existing.uuid);
                            }
                            List<ItemStack> items = vault.contents();
                            var createdVault = vs.createVault(worldId, block.getX(), block.getY(), block.getZ(), finalOwner,
                                    vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                    vault.blockDataString(),
                                    items);
                            UUID newId = createdVault.getUniqueIdentifier();
                            new BukkitRunnable() {
                                @Override public void run() {
                                    var dto = new VaultDtoImp(newId, finalOwner, List.of(),
                                            vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                            null, System.currentTimeMillis());
                                    VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId()).setLastVaultDto(dto);
                                    actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.capturedOk, Map.of("%player%", actor.getName())));
                                    new VaultCaptureMenu(actor).open();
                                }
                            }.runTask(plugin);
                        }
                    }.runTaskAsynchronously(plugin);
                }
            });

            actionbarTask[0] = new BukkitRunnable() {
                @Override public void run() {
                    if (!actor.isOnline()) { cancel(); return; }
                    Block target = actor.getTargetBlockExact(6);
                    if (target == null || !(target.getState() instanceof Container)) {
                        actor.sendActionBar(MiniMessageUtil.parseOrPlain(cfg.actionBarIdle));
                        return;
                    }
                    BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
                    UUID owner = bolt != null ? bolt.getOwner(target) : null;
                    String ownerName = owner == null ? cfg.actionBarUnprotectedOwner :
                            Optional.ofNullable(Bukkit.getOfflinePlayer(owner).getName()).orElse(owner.toString());

                    WorldGuardService wgsLocal = VaultStoragePlugin.getInstance().getWorldGuardService();
                    boolean playerIsRegionOwnerAny = false;
                    boolean playerIsRegionMemberAny = false;
                    boolean containerOwnerIsRegionMemberOrOwnerAny = false;
                    if (wgsLocal != null) {
                        BoundingBox point = new BoundingBox(target.getX(), target.getY(), target.getZ(), target.getX(), target.getY(), target.getZ());
                        var regs = wgsLocal.getRegionsAt(point, target.getWorld());
                        UUID viewer = actor.getUniqueId();
                        for (var r : regs) {
                            if (r.isOwner(viewer)) playerIsRegionOwnerAny = true;
                            if (r.isMember(viewer)) playerIsRegionMemberAny = true;
                            if (owner != null && (r.isOwner(owner) || r.isMember(owner))) {
                                containerOwnerIsRegionMemberOrOwnerAny = true;
                            }
                        }
                    }
                    boolean playerIsContainerOwner = owner != null && owner.equals(actor.getUniqueId());
                    boolean hasOverride = VaultPermission.ACTION_PLACE_OVERRIDE.has(actor);

                    boolean disallowedOwnerSelf = playerIsRegionOwnerAny && playerIsContainerOwner;

                    boolean baseAllowed;
                    if (playerIsRegionOwnerAny) {
                        baseAllowed = (owner != null) && !playerIsContainerOwner && !containerOwnerIsRegionMemberOrOwnerAny;
                    } else if (playerIsRegionMemberAny) {
                        baseAllowed = false;
                    } else {
                        baseAllowed = playerIsContainerOwner;
                    }
                    if (owner == null && !hasOverride) baseAllowed = false;

                    boolean allowed = !disallowedOwnerSelf && (hasOverride ? true : baseAllowed);

                    String vaultable = allowed ? cfg.actionBarVaultableYes : cfg.actionBarVaultableNo;
                    Map<String,String> ph = Map.of("%owner%", ownerName, "%vaultable%", vaultable);
                    actor.sendActionBar(MiniMessageUtil.parseOrPlain(cfg.actionBarContainer, ph));
                }
            }.runTaskTimer(VaultStoragePlugin.getInstance(), 0L, 5L);

            session.getDynamicListener().start();
            actor.closeDialog();
        });

        // Open scan child menu
        builder.button(MiniMessageUtil.parseOrPlain(cfg.openScanBtn, phSelf), ctx -> new VaultScanMenu(ctx.player(), this).open());

        builder.button(MiniMessageUtil.parseOrPlain(cfg.browseBtn, phSelf), ctx -> new VaultListMenu(ctx.player(), this, "").open());
        builder.button(MiniMessageUtil.parseOrPlain(cfg.closeBtn, phSelf), ctx -> {});
        return builder.build();
    }

    @Override
    public void open() {
        // Rebuild the dialog from YAML on each open to reflect live config changes
        setDialog(build());
        super.open();
    }
}
