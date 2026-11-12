package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.jetbrains.annotations.NotNull;
import io.papermc.paper.dialog.Dialog;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public interface Menu {

    @NotNull String id();
    @Nullable
    Dialog dialog();

    void open();

    @NotNull Player player();

    void setDialog(@NotNull Dialog dialog);

    AutoDialog.@NotNull Builder dialogBuilder();


}
