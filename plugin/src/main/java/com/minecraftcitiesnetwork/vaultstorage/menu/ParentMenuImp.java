package com.minecraftcitiesnetwork.vaultstorage.menu;

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
    private Dialog dialog;

    protected ParentMenuImp(@NotNull VaultStoragePlugin plugin, Player player, String id) {
        this.plugin = plugin;
        this.player = player;
        this.id = id;
    }

    @Override
    public @NotNull VaultStoragePlugin plugin() { return plugin; }

    @Override
    public String getId() { return id; }

    @Override
    public Dialog getDialog() { return dialog; }

    @Override
    public void open() {
        if (dialog != null) player.showDialog(dialog);
    }

    @Override
    public Player getPlayer() { return player; }

    @Override
    public void setDialog(Dialog dialog) { this.dialog = dialog; }

    @Override
    public AutoDialog.Builder getAutoDialogBuilder() { return AutoDialog.builder(this); }



}
