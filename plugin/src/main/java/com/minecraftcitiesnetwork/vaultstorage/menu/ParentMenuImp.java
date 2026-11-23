package com.minecraftcitiesnetwork.vaultstorage.menu;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import io.papermc.paper.dialog.Dialog;
import com.minecraftcitiesnetwork.vaultstorage.menu.AutoDialog;
import com.minecraftcitiesnetwork.vaultstorage.menu.ParentMenu;
import org.bukkit.entity.Player;


/**
 * Base implementation of a ParentMenu used to build Paper Dialog UIs.
 * Provides helper methods for common UI tasks (MiniMessage parsing).
 */
public abstract class ParentMenuImp implements ParentMenu {
    protected final @NotNull VaultStoragePlugin plugin;
    protected final Player player;
    private final String id;
    private @Nullable AutoDialog built;

    protected ParentMenuImp(@NotNull VaultStoragePlugin plugin, @NotNull Player player, @NotNull String id) {
        this.plugin = plugin;
        this.player = player;
        this.id = id;
    }

    @Override
    public @NotNull VaultStoragePlugin plugin() { return plugin; }

    @Override
    public @NotNull String id() { return id; }

    @Override
    public @NotNull Dialog dialog() {
        return Objects.requireNonNull(built, "the menu's constructor sets its dialog").dialog();
    }

    @Override
    public void open() {
        if (built == null) {
            return;
        }
        // A module may draw the menu its own way for this player; otherwise it is the dialog.
        if (!plugin.integrations().showMenu(player, built.view())) {
            player.showDialog(built.dialog());
        }
    }

    @Override
    public @NotNull Player player() { return player; }

    @Override
    public void setDialog(@NotNull AutoDialog dialog) { this.built = dialog; }

    @Override
    public AutoDialog.@NotNull Builder dialogBuilder() { return AutoDialog.builder(this); }



}
