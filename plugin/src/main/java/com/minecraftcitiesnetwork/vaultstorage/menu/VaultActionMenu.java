package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.Component;
import java.util.function.Consumer;
import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
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
public final class VaultActionMenu extends ChildMenuImp {

    private static final String KEY_ACTION = "ACTION";

    private final UUID vaultId;
    private final VaultUIContext uiContext;
    private final Component description;
    private final Consumer<Player> back;




    /**
     * @param description the vault's line from the list, shown so the player knows which vault this is
     * @param back        where Back, a finished delete and a finished placement lead
     */
    public VaultActionMenu(@NotNull Player player, @NotNull ParentMenuImp parent, @NotNull VaultUIContext ctx, @NotNull UUID vaultId,
                           @NotNull Component description, @NotNull Consumer<Player> back) {
        super(player, parent, "vault_action");
        this.vaultId = vaultId;
        this.uiContext = ctx;
        this.description = description;
        this.back = back;
        setDialog(build());
    }

    private @NotNull Dialog build() {
        AutoDialog.Builder builder = dialogBuilder();
        builder.title(plugin.text().of(MessageKeys.ACTION_TITLE));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);
        builder.addBody(DialogBody.plainMessage(plugin.messages().messageFor(
                MessageKeys.ACTION_DESCRIPTION, MessageContainer.markup("vault", this.description))));

        // Build SingleOption with available actions filtered by permissions
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        Player player = player();
        String firstValue = null;
        for (VaultAction vaultAction : VaultAction.values()) {
            if (player.hasPermission(vaultAction.permission())) {
                String value = vaultAction.name().toLowerCase(Locale.ROOT);
                boolean selected = firstValue == null; // preselect first permitted
                if (firstValue == null) firstValue = value;
                Map<String,String> ph = Map.of("%action%", cap(vaultAction.name()));
                entries.add(SingleOptionDialogInput.OptionEntry.create(value, plugin.text().of(MessageKeys.ACTION_ACTION_OPTION_FORMAT, ph), selected));
            }
        }
        if (entries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(plugin.text().of(MessageKeys.ACTION_NO_ACTIONS)));
            builder.button(plugin.text().of(MessageKeys.ACTION_BACK_BTN), ctx -> this.back.accept(ctx.player()));
            return builder.build();
        }

        builder.addInput(DialogInput.singleOption(KEY_ACTION, plugin.text().of(MessageKeys.ACTION_ACTION_LABEL), entries).build());

        String defaultValue = firstValue; // fallback when dialog returns null
        builder.buttonWithPlayer(plugin.text().of(MessageKeys.ACTION_OPEN_BTN), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            String selected = Optional.ofNullable(response.getText(KEY_ACTION)).orElse(defaultValue);
            VaultAction action = parseAction(selected);
            actor.sendMessage(plugin.text().of(MessageKeys.ACTION_LOADING));
            openInventoryAsync(actor, action);
        });

        // Place block button (relative placement UI)
        builder.buttonWithPlayer(plugin.text().of(MessageKeys.ACTION_PLACE_BTN), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            new VaultPlacementMenu(actor, (ParentMenuImp) parentMenu(), vaultId, uiContext,
                    again -> new VaultActionMenu(again, (ParentMenuImp) parentMenu(), uiContext, vaultId,
                            this.description, this.back).open(),
                    this.back).open();
        });

        // Admin-only delete button

        if (player.hasPermission(Permissions.ADMIN)) {
            builder.buttonWithPlayer(plugin.text().of(MessageKeys.ACTION_DELETE_BTN), null, Duration.ofMinutes(2), 1, (actor, response) -> {
                if (!actor.hasPermission(Permissions.ADMIN)) {
                    actor.sendMessage(plugin.text().of(MessageKeys.COMMAND_NO_PERMISSION));
                    return;
                }
                MessageContainer messages = plugin.messages();
                new ConfirmMenu(actor, parentMenu(),
                        messages.messageFor(MessageKeys.ACTION_DELETE_CONFIRM_TITLE),
                        messages.messageFor(MessageKeys.ACTION_DELETE_CONFIRM_BODY,
                                MessageContainer.markup("vault", this.description)),
                        messages.messageFor(MessageKeys.ACTION_DELETE_CONFIRM_BTN),
                        messages.messageFor(MessageKeys.ACTION_DELETE_CANCEL_BTN),
                        this::delete,
                        cancelled -> new VaultActionMenu(cancelled, (ParentMenuImp) parentMenu(), uiContext,
                                vaultId, this.description, this.back).open()).open();
            });
        }

        builder.button(plugin.text().of(MessageKeys.ACTION_BACK_BTN), ctx -> this.back.accept(ctx.player()));
        return builder.build();
    }

    /** Deletes the vault for good. Reached only through the confirmation. */
    private void delete(@NotNull Player actor) {
        if (!actor.hasPermission(Permissions.ADMIN)) {
            actor.sendMessage(plugin.text().of(MessageKeys.COMMAND_NO_PERMISSION));
            return;
        }
        actor.sendMessage(plugin.text().of(MessageKeys.ACTION_DELETE_LOADING));
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
                    actor.sendMessage(plugin.text().of(deleted ? MessageKeys.ACTION_DELETE_OK : MessageKeys.ACTION_DELETE_FAIL));
                    this.back.accept(actor);
                }, mainThread);
    }

    private static @NotNull VaultAction parseAction(@NotNull String s) {
        try {
            return VaultAction.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return VaultAction.VIEW;
        }
    }

    private static @NotNull String cap(@NotNull String s) { return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT); }

    private void openInventoryAsync(@NotNull Player player, @NotNull VaultAction action) {
        VaultInventoryService invSvc = plugin.inventoryService();
        invSvc.openVirtualInventory(player, vaultId, action, parentMenu(), () -> new VaultActionMenu(player, (ParentMenuImp) parentMenu(), uiContext, vaultId, this.description, this.back).open());
    }

    @Override
    public void open() {
        // Built on each open, so a reload of messages.yml shows at once.
        setDialog(build());
        super.open();
    }
}
