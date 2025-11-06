package net.democracycraft.vault.api.ui;

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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class AutoDialog {
    private final Dialog internal;

    private AutoDialog(Dialog internal) {
        this.internal = internal;
    }

    public Dialog dialog() {
        return internal;
    }

    public void show(Player player) {
        player.showDialog(internal);
    }

    public static Builder builder(ParentMenu parentMenu) {
        return new Builder(parentMenu);
    }

    public static class Builder {
        private ParentMenu parentMenu;

        private Builder(ParentMenu parentMenu) {
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

        public Builder title(Component title) { this.title = title; return this; }
        public Builder externalTitle(Component externalTitle) { this.externalTitle = externalTitle; return this; }
        public Builder canCloseWithEscape(boolean value) { this.canCloseWithEscape = value; return this; }
        public Builder pause(boolean value) { this.pause = value; return this; }
        public Builder afterAction(DialogBase.DialogAfterAction value) { this.afterAction = value; return this; }

        public Builder body(List<DialogBody> elements) { this.body.addAll(elements); return this; }
        public Builder addBody(DialogBody element) { this.body.add(element); return this; }

        public Builder inputs(List<DialogInput> elements) { this.inputs.addAll(elements); return this; }
        public Builder addInput(DialogInput element) { this.inputs.add(element); return this; }

        public Builder addButton(ActionButton button) { this.buttons.add(button); return this; }

        public ParentMenu getParentMenu() {
            return parentMenu;
        }

        public Builder button(Component label, Component tooltip, Duration expireAfter, int maxUses, Consumer<Context> handler) {
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

        public Builder button(Component label, Consumer<Context> handler) {
            return button(label, null, Duration.ofMinutes(5), 10, handler);
        }

        public Builder buttonWithResponse(Component label, Component tooltip, Duration expireAfter, int maxUses, BiConsumer<DialogResponseView, Audience> handler) {
            ClickCallback.Options options = ClickCallback.Options.builder()
                    .lifetime(expireAfter)
                    .uses(maxUses)
                    .build();

            DialogActionCallback callback = (response, audience) -> handler.accept(response, audience);

            DialogAction action = DialogInstancesProvider.instance().register(callback, options);

            ActionButton btn = ActionButton.builder(label)
                    .tooltip(tooltip)
                    .action(action)
                    .build();

            this.buttons.add(btn);
            return this;
        }

        public Builder buttonWithPlayer(Component label, Component tooltip, Duration expireAfter, int maxUses, BiConsumer<Player, DialogResponseView> handler) {
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

        public Builder commandButton(Component label, Component tooltip, String commandTemplate) {
            DialogAction action = DialogAction.commandTemplate(commandTemplate);
            ActionButton btn = ActionButton.builder(label)
                    .tooltip(tooltip)
                    .action(action)
                    .build();
            this.buttons.add(btn);
            return this;
        }

        public Builder staticButton(Component label, Component tooltip, DialogAction.StaticAction staticAction) {
            ActionButton btn = ActionButton.builder(label)
                    .tooltip(tooltip)
                    .action(staticAction)
                    .build();
            this.buttons.add(btn);
            return this;
        }

        public Dialog build() {
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

    public record Context(Player player, Audience audience, DialogResponseView response) {
    }
}
