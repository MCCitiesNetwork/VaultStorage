package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.command.VaultActionArgument;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.database.Claim;
import com.minecraftcitiesnetwork.vaultstorage.database.ClaimResult;
import com.minecraftcitiesnetwork.vaultstorage.database.DeleteResult;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.vault.PlayerDeleteVaultEvent;
import com.minecraftcitiesnetwork.vaultstorage.vault.VaultAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** One vault: open it to view, edit or copy, place it back, or delete it. */
public final class VaultActionMenu extends ChildMenuImp {

    private static final String KEY_ACTION = "ACTION";

    private final @NotNull UUID vaultId;
    private final @NotNull VaultUIContext uiContext;
    private final @NotNull Component description;
    private final @NotNull Consumer<Player> back;

    /**
     * @param description the vault's line from the list, shown so the player knows which vault this is
     * @param back        where Back, a finished delete and a finished placement lead
     */
    public VaultActionMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx,
                           @NotNull UUID vaultId, @NotNull Component description, @NotNull Consumer<Player> back) {
        super(player, parent);
        this.vaultId = vaultId;
        this.uiContext = ctx;
        this.description = description;
        this.back = back;
    }

    @Override
    protected @NotNull AutoDialog build() {
        AutoDialog.Builder builder = AutoDialog.builder()
                .title(plugin.text().of(MessageKeys.ACTION_TITLE))
                .line(plugin.messages().messageFor(
                        MessageKeys.ACTION_DESCRIPTION, MessageContainer.markup("vault", this.description)));

        // Only the actions the player may use; the first is preselected.
        List<MenuView.Option> options = new ArrayList<>();
        for (VaultAction action : VaultAction.values()) {
            if (player.hasPermission(action.permission())) {
                options.add(new MenuView.Option(action.name().toLowerCase(Locale.ROOT),
                        plugin.text().of(MessageKeys.ACTION_ACTION_OPTION_FORMAT,
                                Map.of("%action%", VaultLabel.materialName(action.name()))),
                        options.isEmpty()));
            }
        }
        if (options.isEmpty()) {
            return builder.line(plugin.text().of(MessageKeys.ACTION_NO_ACTIONS))
                    .button(plugin.text().of(MessageKeys.ACTION_BACK_BTN), this.back)
                    .build();
        }
        builder.choiceInput(KEY_ACTION, plugin.text().of(MessageKeys.ACTION_ACTION_LABEL), options);

        builder.submitButton(plugin.text().of(MessageKeys.ACTION_OPEN_BTN), (actor, answers) -> {
            // The answer comes from the client; openVirtualInventory checks the action's permission.
            VaultAction action = VaultActionArgument.parse(answers.getOrDefault(KEY_ACTION, ""))
                    .orElse(VaultAction.VIEW);
            actor.sendMessage(plugin.text().of(MessageKeys.ACTION_LOADING));
            plugin.inventoryService().openVirtualInventory(actor, vaultId, action, parentMenu(), this::open);
        });
        builder.button(plugin.text().of(MessageKeys.ACTION_PLACE_BTN), actor ->
                new VaultPlacementMenu(actor, parentMenu(), vaultId, uiContext, again -> open(), this.back).open());
        permissionButton(builder, Permissions.ADMIN, plugin.text().of(MessageKeys.ACTION_DELETE_BTN), actor -> {
            MessageContainer messages = plugin.messages();
            new ConfirmMenu(actor, parentMenu(),
                    messages.messageFor(MessageKeys.ACTION_DELETE_CONFIRM_TITLE),
                    messages.messageFor(MessageKeys.ACTION_DELETE_CONFIRM_BODY,
                            MessageContainer.markup("vault", this.description)),
                    messages.messageFor(MessageKeys.ACTION_DELETE_CONFIRM_BTN),
                    messages.messageFor(MessageKeys.ACTION_DELETE_CANCEL_BTN),
                    this::delete,
                    cancelled -> open()).open();
        });
        builder.button(plugin.text().of(MessageKeys.ACTION_BACK_BTN), this.back);
        return builder.build();
    }

    private enum Deletion { DELETED, IN_USE, FAILED }

    /**
     * Deletes the vault for good. Reached only through the confirmation. The vault is claimed
     * first: one that is being edited or placed is not deleted from under whoever has it.
     */
    private void delete(@NotNull Player actor) {
        if (!actor.hasPermission(Permissions.ADMIN)) {
            actor.sendMessage(plugin.text().of(MessageKeys.COMMAND_NO_PERMISSION));
            return;
        }
        actor.sendMessage(plugin.text().of(MessageKeys.ACTION_DELETE_LOADING));
        Executor mainThread = plugin.mainThread();
        CompletableFuture.supplyAsync(() -> plugin.repository().claim(vaultId), plugin.databaseExecutor())
                .thenComposeAsync(claim -> switch (claim) {
                    case ClaimResult.NoSuchVault missing -> CompletableFuture.completedFuture(Deletion.FAILED);
                    case ClaimResult.AlreadyClaimed held -> CompletableFuture.completedFuture(Deletion.IN_USE);
                    case ClaimResult.Claimed claimed -> {
                        // The event is synchronous, so it is fired here, on the main thread.
                        new PlayerDeleteVaultEvent(actor, claimed.vault().vault()).callEvent();
                        yield CompletableFuture.supplyAsync(() -> deleteClaimed(claimed.claim()), plugin.databaseExecutor());
                    }
                }, mainThread)
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not delete vault " + vaultId + ".", failure);
                    return Deletion.FAILED;
                })
                .thenAcceptAsync(deletion -> {
                    actor.sendMessage(plugin.text().of(switch (deletion) {
                        case DELETED -> MessageKeys.ACTION_DELETE_OK;
                        case IN_USE -> MessageKeys.INVENTORY_IN_USE;
                        case FAILED -> MessageKeys.ACTION_DELETE_FAIL;
                    }));
                    this.back.accept(actor);
                }, mainThread);
    }

    /** Database thread. The vault is claimed by this delete; a failed delete gives it back. */
    private @NotNull Deletion deleteClaimed(@NotNull Claim claim) {
        try {
            return plugin.repository().delete(claim) instanceof DeleteResult.Deleted ? Deletion.DELETED : Deletion.FAILED;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not delete vault " + vaultId + ".", ex);
            plugin.claimReleases().release(claim);
            return Deletion.FAILED;
        }
    }
}
