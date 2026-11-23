package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** What every menu shares: who it is for, how it is shown, and buttons that need a permission. */
public abstract class ParentMenuImp implements ParentMenu {

    protected final @NotNull VaultStoragePlugin plugin;
    protected final @NotNull Player player;

    protected ParentMenuImp(@NotNull VaultStoragePlugin plugin, @NotNull Player player) {
        this.plugin = plugin;
        this.player = player;
    }

    /** The screen as it is now. Called on each open, so a reload of messages.yml shows at once. */
    protected abstract @NotNull AutoDialog build();

    @Override
    public void open() {
        AutoDialog built = build();
        // A module may draw the menu its own way for this player; otherwise it is the dialog.
        if (!this.plugin.integrations().showMenu(this.player, built.view())) {
            this.player.showDialog(built.dialog());
        }
    }

    @Override
    public @NotNull VaultStoragePlugin plugin() {
        return this.plugin;
    }

    @Override
    public @NotNull Player player() {
        return this.player;
    }

    /**
     * A button only a player with the permission is shown. The permission is checked again at
     * the click: the menu may be older than the permission, and its answer comes from the client.
     */
    protected final void permissionButton(AutoDialog.@NotNull Builder builder, @NotNull String permission,
                                          @NotNull Component label, @NotNull Consumer<Player> action) {
        if (!this.player.hasPermission(permission)) {
            return;
        }
        builder.button(label, actor -> {
            if (actor.hasPermission(permission)) {
                action.accept(actor);
            } else {
                actor.sendMessage(this.plugin.text().of(MessageKeys.COMMAND_NO_PERMISSION));
            }
        });
    }
}
