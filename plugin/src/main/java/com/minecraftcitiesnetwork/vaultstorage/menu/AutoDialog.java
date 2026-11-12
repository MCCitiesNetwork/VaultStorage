package com.minecraftcitiesnetwork.vaultstorage.menu;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.InlinedRegistryBuilderProvider;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Range;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AutoDialog {
    private final Dialog internal;

    private AutoDialog(@NotNull Dialog internal) {
        this.internal = internal;
    }



    public static @NotNull Builder builder(@NotNull ParentMenu parentMenu) {
        return new Builder(parentMenu);
    }

    public static class Builder {
        private ParentMenu parentMenu;

        private Builder(@NotNull ParentMenu parentMenu) {
            this.parentMenu = parentMenu;
        }


        private Component title = Component.text("Dialog");
        private Component externalTitle = null;
        private boolean canCloseWithEscape = true;
        private boolean pause = true;
        private DialogBase.DialogAfterAction afterAction = DialogBase.DialogAfterAction.CLOSE;

        private final List<DialogBody> body = new ArrayList<>();
        private final List<DialogInput> inputs = new ArrayList<>();
        private final List<ActionButton> buttons = new ArrayList<>();

        public @NotNull Builder title(@NotNull Component title) {
            this.title = title;
            return this;
        }
        public @NotNull Builder canCloseWithEscape(boolean value) {
            this.canCloseWithEscape = value;
            return this;
        }
        public @NotNull Builder afterAction(DialogBase.@NotNull DialogAfterAction value) {
            this.afterAction = value;
            return this;
        }

        public @NotNull Builder addBody(@NotNull DialogBody element) {
            this.body.add(element);
            return this;
        }

        public @NotNull Builder addInput(@NotNull DialogInput element) {
            this.inputs.add(element);
            return this;
        }


        public @NotNull ParentMenu parentMenu() {
            return parentMenu;
        }

        public @NotNull Builder button(@NotNull Component label, @Nullable Component tooltip, @NotNull Duration expireAfter, int maxUses, @NotNull Consumer<Context> handler) {
            Objects.requireNonNull(label);
            ClickCallback.Options options = ClickCallback.Options.builder()
                    .lifetime(expireAfter)
                    .uses(maxUses)
                    .build();

            DialogActionCallback callback = (response, audience) -> {
                Player player = (audience instanceof Player) ? (Player) audience : null;
                if (player == null) return;
                Context ctx = new Context(player, audience, response);
                handler.accept(ctx);
            };

            DialogAction action = DialogInstancesProvider.instance().register(callback, options);

            ActionButton btn = ActionButton.builder(label)
                    .tooltip(tooltip)
                    .action(action)
                    .build();

            this.buttons.add(btn);
            return this;
        }

        public @NotNull Builder sizableButton(@NotNull Component label, @Nullable Component tooltip, @NotNull Duration expireAfter, int maxUses, @NotNull Consumer<Context> handler, @Range(from = 1, to = 1024) int width) {
            Objects.requireNonNull(label);
            ClickCallback.Options options = ClickCallback.Options.builder()
                    .lifetime(expireAfter)
                    .uses(maxUses)
                    .build();

            DialogActionCallback callback = (response, audience) -> {
                Player player = (audience instanceof Player) ? (Player) audience : null;
                if (player == null) return;
                Context ctx = new Context(player, audience, response);
                handler.accept(ctx);
            };

            DialogAction action = DialogInstancesProvider.instance().register(callback, options);

            ActionButton btn = ActionButton.builder(label)
                    .tooltip(tooltip)
                    .action(action)
                    .width(width)
                    .build();

            this.buttons.add(btn);
            return this;
        }

        public @NotNull Builder sizableButton(@NotNull Component label, @NotNull Consumer<Context> handler, @Range(from = 1, to = 1024) int width) {
            return sizableButton(label, null, Duration.ofMinutes(5), 10, handler, width);
        }

        public @NotNull Builder button(@NotNull Component label, @NotNull Consumer<Context> handler) {
            return button(label, null, Duration.ofMinutes(5), 10, handler);
        }


        public @NotNull Builder buttonWithPlayer(@NotNull Component label, @Nullable Component tooltip, @NotNull Duration expireAfter, int maxUses, @NotNull BiConsumer<Player, DialogResponseView> handler) {
            ClickCallback.Options options = ClickCallback.Options.builder()
                    .lifetime(expireAfter)
                    .uses(maxUses)
                    .build();

            DialogActionCallback callback = (response, audience) -> {
                Player player = (audience instanceof Player) ? (Player) audience : null;
                if (player == null) return;
                handler.accept(player, response);
            };

            DialogAction action = DialogInstancesProvider.instance().register(callback, options);

            ActionButton btn = ActionButton.builder(label)
                    .tooltip(tooltip)
                    .action(action)
                    .build();

            this.buttons.add(btn);
            return this;
        }



        public @NotNull Dialog build() {
            DialogBase.Builder baseBuilder = DialogBase.builder(title)
                    .canCloseWithEscape(canCloseWithEscape)
                    .pause(pause)
                    .afterAction(afterAction);

            if (externalTitle != null) baseBuilder.externalTitle(externalTitle);
            if (!body.isEmpty()) baseBuilder.body(body);
            if (!inputs.isEmpty()) baseBuilder.inputs(inputs);

            return InlinedRegistryBuilderProvider.instance().createDialog(factory -> {
                var builder = factory.empty();

                builder.base(baseBuilder.build());

                if (!buttons.isEmpty()) {
                    builder.type(DialogType.multiAction(buttons).build());
                } else {
                    builder.type(DialogInstancesProvider.instance().notice());
                }
             });
         }
     }

    public record Context(@NotNull Player player, @NotNull Audience audience, @NotNull DialogResponseView response) {
    }
}
