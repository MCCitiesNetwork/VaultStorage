package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.config.Text;
import com.minecraftcitiesnetwork.vaultstorage.database.DeleteResult;
import com.minecraftcitiesnetwork.vaultstorage.database.FindResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultAction;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.vault.PlayerDeleteVaultEvent;
import com.minecraftcitiesnetwork.vaultstorage.menu.AutoDialog;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultInventoryService;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Child dialog that lets a player choose an action (view, edit, copy) for a vault.
 * Database work runs on the database executor.
 */
public class VaultActionMenu extends ChildMenuImp {

    private static final String KEY_ACTION = "ACTION";

    private final UUID vaultId;
    private final VaultUIContext uiContext;




    public VaultActionMenu(Player player, ParentMenuImp parent, VaultUIContext ctx, UUID vaultId) {
        super(player, parent, "vault_action");
        this.vaultId = vaultId;
        this.uiContext = ctx;
        setDialog(build());
    }

    private Dialog build() {
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(Text.of(MessageKeys.ACTION_TITLE));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        // Build SingleOption with available actions filtered by permissions
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        Player player = getPlayer();
        String firstValue = null;
        for (VaultAction vaultAction : VaultAction.values()) {
            if (player.hasPermission(vaultAction.permission())) {
                String value = vaultAction.name().toLowerCase(Locale.ROOT);
                boolean selected = firstValue == null; // preselect first permitted
                if (firstValue == null) firstValue = value;
                Map<String,String> ph = Map.of("%action%", cap(vaultAction.name()));
                entries.add(SingleOptionDialogInput.OptionEntry.create(value, Text.of(MessageKeys.ACTION_ACTION_OPTION_FORMAT, ph), selected));
            }
        }
        if (entries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.ACTION_NO_ACTIONS)));
            builder.button(Text.of(MessageKeys.ACTION_BACK_BTN), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), uiContext,"").open());
            return builder.build();
        }

        builder.addInput(DialogInput.singleOption(KEY_ACTION, Text.of(MessageKeys.ACTION_ACTION_LABEL), entries).build());

        String defaultValue = firstValue; // fallback when dialog returns null
        builder.buttonWithPlayer(Text.of(MessageKeys.ACTION_OPEN_BTN), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            String selected = Optional.ofNullable(response.getText(KEY_ACTION)).orElse(defaultValue);
            VaultAction action = parseAction(selected);
            actor.sendMessage(Text.of(MessageKeys.ACTION_LOADING));
            openInventoryAsync(actor, action);
        });

        // Place block button (relative placement UI)
        builder.buttonWithPlayer(Text.of(MessageKeys.ACTION_PLACE_BTN), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            new VaultPlacementMenu(actor, (ParentMenuImp) getParentMenu(), vaultId, uiContext).open();
        });

        // Admin-only delete button

        if (player.hasPermission(Permissions.ADMIN)) {
            builder.buttonWithPlayer(Text.of(MessageKeys.ACTION_DELETE_BTN), null, Duration.ofMinutes(2), 1, (actor, response) -> {
                if (!actor.hasPermission(Permissions.ADMIN)) {
                    actor.sendMessage(Text.of(MessageKeys.COMMAND_NO_PERMISSION));
                    return;
                }
                actor.sendMessage(Text.of(MessageKeys.ACTION_DELETE_LOADING));
                var plugin = VaultStoragePlugin.getInstance();
                Executor mainThread = plugin.getServer().getScheduler().getMainThreadExecutor(plugin);
                CompletableFuture.supplyAsync(() -> plugin.repository().find(vaultId), plugin.databaseExecutor())
                        .thenComposeAsync(found -> {
                            if (!(found instanceof FindResult.Found vault)) {
                                return CompletableFuture.completedFuture(false);
                            }
                            // The event is synchronous, so it is fired here, on the main thread.
                            new PlayerDeleteVaultEvent(actor, vault.vault().vault()).callEvent();
                            return CompletableFuture.supplyAsync(
                                    () -> plugin.repository().delete(vaultId) instanceof DeleteResult.Deleted,
                                    plugin.databaseExecutor());
                        }, mainThread)
                        .exceptionally(failure -> {
                            plugin.getLogger().log(Level.SEVERE, "Could not delete vault " + vaultId + ".", failure);
                            return false;
                        })
                        .thenAcceptAsync(deleted -> {
                            actor.sendMessage(Text.of(deleted ? MessageKeys.ACTION_DELETE_OK : MessageKeys.ACTION_DELETE_FAIL));
                            if (getParentMenu() instanceof ParentMenuImp parent) {
                                new VaultListMenu(actor, parent, uiContext, "").open();
                            } else {
                                actor.closeDialog();
                            }
                        }, mainThread);
            });
        }

        builder.button(Text.of(MessageKeys.ACTION_BACK_BTN), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), uiContext, "").open());
        return builder.build();
    }

    private static VaultAction parseAction(String s) {
        try {
            return VaultAction.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return VaultAction.VIEW;
        }
    }

    private static String cap(String s) { return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT); }

    private void openInventoryAsync(Player player, VaultAction action) {
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
