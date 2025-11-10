package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.security.VaultCapturePolicy;
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

import java.util.*;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import net.democracycraft.vault.internal.util.item.ItemSerialization;

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
        /** Chat message when target container is empty and nothing is persisted. Supports %player%. */
        public String emptyCaptureSkipped = "Container empty; nothing captured.";
        /** Button to open the scan menu. */
        public String openScanBtn = "<yellow>Scan Region</yellow>";
        /** Actionbar while in capture mode (when not looking at a container). */
        public String actionBarIdle = "<yellow>Capture mode</yellow> - Right-click a container. <gray>Left-click to cancel.</gray>";
        /** Actionbar when looking at a container. Placeholders: %owner% %vaultable% %reasonSegment% %player% %regions% %reasonCode% */
        public String actionBarContainer = "<gray>Owner:</gray> <white>%owner%</white> <gray>| Vaultable:</gray> <white>%vaultable%</white><gray>%reasonSegment%</gray>";
        /** Segment prefix appended when NOT vaultable: contains %reason% placeholder. */
        public String actionBarReasonSegmentTemplate = " | Reason: %reason%";
        /** Placeholder shown when there is no blocking reason (allowed). */
        public String actionBarReasonAllowedBlank = "";
        /** Reason text when actor owns region and container (self restriction). Placeholders: %player% %owner% %regions% */
        public String reasonOwnerSelfInRegion = "you own region and container";
        /** Reason text when container owner is member/owner of overlapping region blocking capture. Placeholders: %player% %owner% %regions% */
        public String reasonContainerOwnerInOverlap = "container owner is member/owner of region";
        /** Reason text when actor is only a member (not owner). Placeholders: %player% %owner% %regions% */
        public String reasonActorMemberBlocked = "region member cannot capture";
        /** Reason text when actor not involved and not container owner. Placeholders: %player% %owner% %regions% */
        public String reasonNotInvolvedNotOwner = "not involved and not container owner";
        /** Reason text when container unprotected and no override. Placeholders: %player% %owner% %regions% */
        public String reasonUnprotectedNoOverride = "unprotected container and no override";
        /** Fallback reason text. */
        public String reasonFallback = "cannot";
        /** Message when actor not allowed by region/container policy. */
        public String notAllowed = "<red>Not allowed: region/container rules.</red>";
        /** Message when no Bolt owner exists and override will set the actor as owner. */
        public String noBoltOwner = "<yellow>No Bolt owner found; you will be set as the vault owner.</yellow>";
        /** Text used when the container has no Bolt protection (owner unknown). */
        public String actionBarUnprotectedOwner = "unprotected";
        /** Text shown when a container is vaultable. */
        public String actionBarVaultableYes = "yes";
        /** Text shown when a container is NOT vaultable. */
        public String actionBarVaultableNo = "no";
        // Deprecated old fields kept for backward compatibility (not used now)
        /** @deprecated legacy field; replaced by actionBarReasonSegmentTemplate + individual reason texts */
        @Deprecated public String reasonAllowedBlank = "";
        @Deprecated public String reasonActorMemberBlockedLegacy = "(cannot: region member)";
    }

    private static final String HEADER = String.join("\n",
            "VaultCaptureMenu configuration.",
            "All strings accept MiniMessage or plain text.",
            "General placeholders:",
            "- %player% -> current player name",
            "- %owner%  -> container Bolt owner name/UUID or 'unprotected' text",
            "- %vaultable% -> yes/no value (actionBarVaultableYes / actionBarVaultableNo)",
            "- %reasonSegment% -> preformatted segment starting with prefix (blank if allowed)",
            "- %reason% -> resolved human readable reason text (inside reason segment template)",
            "- %reasonCode% -> enum code (OWNER_SELF_IN_REGION, CONTAINER_OWNER_IN_OVERLAP, ACTOR_MEMBER_BLOCKED, NOT_INVOLVED_NOT_OWNER, UNPROTECTED_NO_OVERRIDE, ALLOWED)",
            "- %regions% -> comma separated region ids overlapping target (action bar only)",
            "Capture flow placeholders:",
            "- Used inside reason* fields: %player% %owner% %regions% %reasonCode%",
            "Customization notes:",
            "- Set actionBarReasonSegmentTemplate to blank to hide reasons entirely.",
            "- Provide your own coloring/formatting per reason* field.",
            "- Deprecated fields retained for backward compatibility: reasonAllowedBlank, reasonActorMemberBlockedLegacy." );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultCaptureMenu", DataFolder.MENUS, HEADER);
    private static Config cfg() { return YML.loadOrCreate(Config::new); }
    /** Ensures the YAML file for this menu exists by creating defaults if missing. */
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }

    private final VaultUIContext uiContext;

    /** Existing constructor kept: self-filter context */
    public VaultCaptureMenu(Player player) {
        this(player, VaultUIContext.self(player.getUniqueId()));
    }

    /** New constructor accepting UI context (admin or filtered). */
    public VaultCaptureMenu(Player player, VaultUIContext context) {
        super(player, "vault_capture");
        this.uiContext = context;
        setDialog(build());
    }

    /**
     * Builds the dialog structure and associated actions.
     * @return the constructed dialog instance
     */
    private Dialog build() {
        Config cfg = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> phSelf = new java.util.HashMap<>();
        phSelf.put("%player%", getPlayer().getName());
        if (uiContext.filterOwner() != null) {
            phSelf.put("%filterOwner%", uiContext.filterOwner().toString());
        } else if (uiContext.admin()) {
            phSelf.put("%filterOwner%", "ALL");
        }
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

                    BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
                    // Policy decision
                    VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluateWithLog(actor, block, "CaptureMenuCheck");
                    UUID originalOwner = decision.containerOwner();

                    if (!decision.allowed()) {
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.notAllowed, Map.of("%player%", actor.getName())));
                        new VaultCaptureMenu(actor).open();
                        return;
                    }

                    if (bolt != null && originalOwner == null && decision.hasOverride()) {
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.noBoltOwner));
                    }
                    if (bolt != null) {
                        try { bolt.removeProtection(block); } catch (Throwable ignored) {}
                    }

                    VaultCaptureService captureService = VaultStoragePlugin.getInstance().getCaptureService();
                    // Unified emptiness check (handles double chests via block inventory)
                    boolean empty;
                    try { empty = captureService.isContainerEmpty(block); } catch (IllegalArgumentException ex) { empty = true; }
                    if (empty) {
                        actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.emptyCaptureSkipped, Map.of("%player%", actor.getName())));
                        new VaultCaptureMenu(actor).open();
                        return;
                    }

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
                            // Create vault row and obtain its UUID before batching items
                            UUID newId;
                            {
                                var created = vs.createVault(worldId, block.getX(), block.getY(), block.getZ(), finalOwner,
                                        vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                        vault.blockDataString());
                                newId = created.uuid;
                            }
                            List<ItemStack> items = vault.contents();
                            // Batch persist items (single round-trip) instead of per-slot writes
                            java.util.List<VaultItemEntity> batch = new java.util.ArrayList<>(items.size());
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
                                vs.putItems(newId, batch);
                            }
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

                    VaultCapturePolicy.Decision decision = VaultCapturePolicy.evaluate(actor, target);
                    String vaultable = decision.allowed() ? cfg.actionBarVaultableYes : cfg.actionBarVaultableNo;
                    // Regions list for placeholders
                    String regionsList = decision.regionStatuses().stream().map(VaultCapturePolicy.RegionStatus::regionId).sorted().reduce((a, b)->a+", "+b).orElse("");
                    String reasonSegment = getReasonSegment(decision, ownerName, regionsList, cfg, actor);
                    Map<String,String> ph = Map.of(
                            "%owner%", ownerName,
                            "%vaultable%", vaultable,
                            "%reasonSegment%", reasonSegment,
                            "%regions%", regionsList,
                            "%reasonCode%", decision.reason().name()
                    );
                    actor.sendActionBar(MiniMessageUtil.parseOrPlain(cfg.actionBarContainer, ph));
                }
            }.runTaskTimer(VaultStoragePlugin.getInstance(), 0L, 5L);

            session.getDynamicListener().start();
            actor.closeDialog();
        });

        // Open scan child menu
        builder.button(MiniMessageUtil.parseOrPlain(cfg.openScanBtn, phSelf), ctx -> new VaultScanMenu(ctx.player(), this, uiContext).open());

        builder.button(MiniMessageUtil.parseOrPlain(cfg.browseBtn, phSelf), ctx -> new VaultListMenu(ctx.player(), this, uiContext, "").open());
        builder.button(MiniMessageUtil.parseOrPlain(cfg.closeBtn, phSelf), ctx -> {});
        return builder.build();
    }

    /** Public accessor for empty capture message (used by command subcaptures). */
    public static String emptyCaptureMessage() { return cfg().emptyCaptureSkipped; }

    private static String getReasonSegment(VaultCapturePolicy.Decision decision, String ownerName, String regionsList, Config cfg, Player actor) {
        String reasonText;
        if (decision.allowed()) {
            reasonText = cfg.actionBarReasonAllowedBlank;
        } else {
            switch (decision.reason()) {
                case OWNER_SELF_IN_REGION -> reasonText = cfg.reasonOwnerSelfInRegion;
                case CONTAINER_OWNER_IN_OVERLAP -> reasonText = cfg.reasonContainerOwnerInOverlap;
                case ACTOR_MEMBER_BLOCKED -> reasonText = cfg.reasonActorMemberBlocked;
                case NOT_INVOLVED_NOT_OWNER -> reasonText = cfg.reasonNotInvolvedNotOwner;
                case UNPROTECTED_NO_OVERRIDE -> reasonText = cfg.reasonUnprotectedNoOverride;
                default -> reasonText = cfg.reasonFallback;
            }
            Map<String,String> reasonPh = Map.of(
                    "%player%", actor.getName(),
                    "%owner%", ownerName,
                    "%regions%", regionsList,
                    "%reasonCode%", decision.reason().name()
            );
            // Manual placeholder replacement to keep String type
            String resolved = reasonText;
            for (var e : reasonPh.entrySet()) {
                resolved = resolved.replace(e.getKey(), e.getValue());
            }
            reasonText = resolved;
        }
        return decision.allowed() ? cfg.actionBarReasonAllowedBlank : cfg.actionBarReasonSegmentTemplate.replace("%reason%", reasonText);
    }

    @Override
    public void open() {
        // Rebuild the dialog from YAML on each open to reflect live config changes
        setDialog(build());
        super.open();
    }
}
