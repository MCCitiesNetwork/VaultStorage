package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.service.VaultInventoryService;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.*;

/**
 * Child dialog that lets a player choose an action (view, edit, copy) for a vault.
 * Uses VaultService asynchronously to avoid blocking the main thread.
 */
public class VaultActionMenu extends ChildMenuImp {

    private static final String KEY_ACTION = "ACTION";

    private final UUID vaultId;
    private final VaultUIContext uiContext;

    /** Configurable menu texts. */
    public static class Config implements Dto, java.io.Serializable {
        /** Dialog title. */
        public String title = "<gold><bold>Vault Action</bold></gold>";
        /** Label for the action selector. */
        public String actionLabel = "<gray>Select action</gray>";
        /** Button to open the chosen action. */
        public String openBtn = "<green><bold>Open Inventory</bold></green>";
        /** Button to place the vault block back into the world. */
        public String placeBtn = "<yellow><bold>Place Block</bold></yellow>";
        /** Back button to return to list. */
        public String backBtn = "<red><bold>Back</bold></red>";
        /** Message when target vault is not found. */
        public String notFound = "<red><bold>Vault not found.</bold></red>";
        /** Message when no actions are available due to permissions. */
        public String noActions = "<red><bold>No actions available.</bold></red>";
        /** Label format for action options (placeholder %action%). */
        public String actionOptionFormat = "<white>%action%</white>";
        /** Inventory title when opening virtual inventory. Placeholders: %id% %action% */
        public String inventoryTitle = "<white>Vault %id% - %action%</white>";
        /** Loading message for chat when preparing inventory. */
        public String loading = "<gray>Loading vault...</gray>";
        /** Saved message after edit. */
        public String saved = "<green>Vault saved.</green>";
        /** Placing start message. */
        public String placing = "<gray>Placing block...</gray>";
        /** Placement success prefix. Placeholder %msg%. */
        public String placeOk = "<green>%msg%</green>";
        /** Placement failure prefix. Placeholder %msg%. */
        public String placeFail = "<red>%msg%</red>";
        /** Button label for deleting the vault (admin only). */
        public String deleteBtn = "<red><bold>Delete Vault</bold></red>";
        /** Chat message shown after successful deletion. */
        public String deleteOk = "<green>Vault deleted.</green>";
        /** Chat message shown if deletion fails. */
        public String deleteFail = "<red>Vault deletion failed.</red>";
        /** Chat message shown while deletion runs asynchronously. */
        public String deleteLoading = "<gray>Deleting vault...</gray>";
    }

    private static final String HEADER = String.join("\n",
            "VaultActionMenu configuration.",
            "Fields accept MiniMessage or plain strings.",
            "Placeholders for actionOptionFormat:",
            "- %action% -> formatted action name (View/Copy/Edit)",
            "Placeholders for inventoryTitle:",
            "- %id% -> vault UUID",
            "- %action% -> selected action (View/Copy/Edit)",
            "Placeholders for placement messages:",
            "- %msg% -> result message from placement service"
    );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultActionMenu", DataFolder.MENUS, HEADER);
    /** Ensures the YAML file for this menu exists by creating defaults if missing. */
    public static void ensureConfig() {
        YML.loadOrCreate(Config::new);
    }
    private static VaultActionMenu.Config cfg() {
        return YML.loadOrCreate(VaultActionMenu.Config::new);
    }

    public VaultActionMenu(Player player, ParentMenuImp parent, VaultUIContext ctx, UUID vaultId) {
        super(player, parent, "vault_action");
        this.vaultId = vaultId;
        this.uiContext = ctx;
        setDialog(build());
    }

    private Dialog build() {
        Config cfg = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(MiniMessageUtil.parseOrPlain(cfg.title));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        // Build SingleOption with available actions filtered by permissions
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        Player player = getPlayer();
        String firstValue = null;
        for (VaultAction vaultAction : VaultAction.values()) {
            if (vaultAction.permission().has(player)) {
                String value = vaultAction.name().toLowerCase(Locale.ROOT);
                boolean selected = firstValue == null; // preselect first permitted
                if (firstValue == null) firstValue = value;
                Map<String,String> ph = Map.of("%action%", cap(vaultAction.name()));
                entries.add(SingleOptionDialogInput.OptionEntry.create(value, MiniMessageUtil.parseOrPlain(cfg.actionOptionFormat, ph), selected));
            }
        }
        if (entries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.noActions)));
            builder.button(MiniMessageUtil.parseOrPlain(cfg.backBtn), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), uiContext,"").open());
            return builder.build();
        }

        builder.addInput(DialogInput.singleOption(KEY_ACTION, MiniMessageUtil.parseOrPlain(cfg.actionLabel), entries).build());

        String defaultValue = firstValue; // fallback when dialog returns null
        builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(cfg.openBtn), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            String selected = Optional.ofNullable(response.getText(KEY_ACTION)).orElse(defaultValue);
            VaultAction action = parseAction(selected);
            actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.loading));
            openInventoryAsync(actor, action, cfg);
        });

        // Place block button (relative placement UI)
        builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(cfg.placeBtn), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            new VaultPlacementMenu(actor, (ParentMenuImp) getParentMenu(), vaultId, uiContext).open();
        });

        // Admin-only delete button
        /**
         * Admin delete operation:
         * Visible only if actor has ADMIN permission. On click it re-validates permission,
         * sends a loading message, performs asynchronous deletion (with existence check),
         * then returns to the vault list (or closes dialog if parent missing).
         */
        if (VaultPermission.ADMIN.has(player)) {
            builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(cfg.deleteBtn), null, Duration.ofMinutes(2), 1, (actor, response) -> {
                if (!net.democracycraft.vault.internal.security.VaultPermission.ADMIN.has(actor)) {
                    actor.sendMessage("You don't have permission.");
                    return;
                }
                actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.deleteLoading));
                var plugin = net.democracycraft.vault.VaultStoragePlugin.getInstance();
                new BukkitRunnable() {
                    @Override public void run() {
                        boolean success;
                        try {
                            var vaultService = plugin.getVaultService();
                            var exists = vaultService.get(vaultId).isPresent();
                            if (!exists) {
                                success = false;
                            } else {
                                vaultService.delete(vaultId);
                                success = true;
                            }
                        } catch (Throwable t) { success = false; }
                        final boolean ok = success;
                        new BukkitRunnable() {
                            @Override public void run() {
                                actor.sendMessage(MiniMessageUtil.parseOrPlain(ok ? cfg.deleteOk : cfg.deleteFail));
                                if (getParentMenu() instanceof ParentMenuImp parent) {
                                    new VaultListMenu(actor, parent, uiContext, "").open();
                                } else {
                                    actor.closeDialog();
                                }
                            }
                        }.runTask(plugin);
                    }
                }.runTaskAsynchronously(plugin);
            });
        }

        builder.button(MiniMessageUtil.parseOrPlain(cfg.backBtn), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), uiContext, "").open());
        return builder.build();
    }

    private static VaultAction parseAction(String s) {
        try { return VaultAction.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { return VaultAction.VIEW; }
    }

    private static String cap(String s) { return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT); }

    private void openInventoryAsync(Player player, VaultAction action, Config cfg) {
        VaultInventoryService invSvc = VaultStoragePlugin.getInstance().getInventoryService();
        invSvc.openVirtualInventory(player, vaultId, action, getParentMenu(), () -> new VaultActionMenu(player, (ParentMenuImp) getParentMenu(), uiContext, vaultId).open());
    }

    @Override
    public void open() {
        // Rebuild the dialog from YAML on each open to reflect live config changes
        setDialog(build());
        super.open();
    }
}
