package net.democracycraft.vault.api.ui;

import io.papermc.paper.dialog.Dialog;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public interface Menu {

    String getId();
    @Nullable
    Dialog getDialog();

    void open();

    Player getPlayer();

    void setDialog(Dialog dialog);

    AutoDialog.Builder getAutoDialogBuilder();


    default String getMenuConfigFileName() {
        return this.getClass().getSimpleName();
    }
}
