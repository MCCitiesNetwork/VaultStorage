package com.minecraftcitiesnetwork.vaultstorage.menu;

import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Asks before something that cannot be undone. Closing the dialog confirms nothing. */
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
        super(player, parent, "confirm");
        this.title = title;
        this.body = body;
        this.confirmLabel = confirmLabel;
        this.cancelLabel = cancelLabel;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        setDialog(build());
    }

    private @NotNull AutoDialog build() {
        AutoDialog.Builder builder = dialogBuilder();
        builder.title(this.title);
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);
        builder.addBody(DialogBody.plainMessage(this.body));
        // Cancel first: it is the button under the pointer when the dialog opens.
        builder.button(this.cancelLabel, context -> this.onCancel.accept(context.player()));
        builder.button(this.confirmLabel, context -> this.onConfirm.accept(context.player()));
        return builder.build();
    }
}
