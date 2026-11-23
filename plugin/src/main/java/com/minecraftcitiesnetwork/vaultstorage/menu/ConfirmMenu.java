package com.minecraftcitiesnetwork.vaultstorage.menu;

import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Asks before something that cannot be undone. Cancel is the first button. */
public final class ConfirmMenu extends ChildMenuImp {

    private final @NotNull Component title;
    private final @NotNull Component body;
    private final @NotNull Component confirmLabel;
    private final @NotNull Component cancelLabel;
    private final @NotNull Consumer<Player> onConfirm;
    private final @NotNull Consumer<Player> onCancel;

    public ConfirmMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull Component title,
                       @NotNull Component body, @NotNull Component confirmLabel, @NotNull Component cancelLabel,
                       @NotNull Consumer<Player> onConfirm, @NotNull Consumer<Player> onCancel) {
        super(player, parent);
        this.title = title;
        this.body = body;
        this.confirmLabel = confirmLabel;
        this.cancelLabel = cancelLabel;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    @Override
    protected @NotNull AutoDialog build() {
        return AutoDialog.builder()
                .title(this.title)
                .line(this.body)
                // Cancel first: it is the button under the pointer when the dialog opens.
                .button(this.cancelLabel, this.onCancel)
                .button(this.confirmLabel, this.onConfirm)
                .build();
    }
}
