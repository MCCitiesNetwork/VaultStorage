package com.minecraftcitiesnetwork.vaultstorage.menu;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.InlinedRegistryBuilderProvider;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogInstancesProvider;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A menu as built: its description, a {@link MenuView}, and the Paper dialog made from it. A
 * menu is described once, so what a dialog shows and what a {@link MenuRenderer} is given
 * cannot differ.
 */
public final class AutoDialog {

    /** How long a button answers after the dialog was sent, and how often. */
    private static final ClickCallback.Options CLICKS = ClickCallback.Options.builder()
            .lifetime(Duration.ofMinutes(5)).uses(10).build();

    private final @NotNull MenuView view;
    private final @NotNull Dialog dialog;

    private AutoDialog(@NotNull MenuView view, @NotNull Dialog dialog) {
        this.view = view;
        this.dialog = dialog;
    }

    public @NotNull MenuView view() {
        return this.view;
    }

    public @NotNull Dialog dialog() {
        return this.dialog;
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Component title = Component.empty();
        private final List<Component> lines = new ArrayList<>();
        private final List<MenuView.Input> inputs = new ArrayList<>();
        private final List<MenuView.Button> buttons = new ArrayList<>();

        private Builder() {
        }

        public @NotNull Builder title(@NotNull Component title) {
            this.title = title;
            return this;
        }

        /** A line of text above the inputs and buttons. */
        public @NotNull Builder line(@NotNull Component text) {
            this.lines.add(text);
            return this;
        }

        public @NotNull Builder textInput(@NotNull String key, @NotNull Component label, int maxLength) {
            this.inputs.add(new MenuView.Text(key, label, "", maxLength));
            return this;
        }

        public @NotNull Builder choiceInput(@NotNull String key, @NotNull Component label,
                                            @NotNull List<MenuView.Option> options) {
            this.inputs.add(new MenuView.Choice(key, label, options));
            return this;
        }

        public @NotNull Builder button(@NotNull Component label, @NotNull Consumer<Player> action) {
            return wideButton(label, 0, action);
        }

        /** @param width the button's width in a dialog */
        public @NotNull Builder wideButton(@NotNull Component label, int width, @NotNull Consumer<Player> action) {
            return tooltipButton(label, null, width, action);
        }

        /**
         * A button that says more while the pointer is over it. The tooltip repeats what the
         * menu it leads to says: a client without tooltips shows the label alone.
         */
        public @NotNull Builder tooltipButton(@NotNull Component label, @Nullable Component tooltip, int width,
                                              @NotNull Consumer<Player> action) {
            this.buttons.add(new MenuView.Button(label, tooltip, false, width,
                    (player, answers) -> action.accept(player)));
            return this;
        }

        /**
         * A button whose action reads the menu's inputs. Said apart from the others because a
         * client that cannot show inputs beside buttons asks for them when this one is pressed.
         */
        public @NotNull Builder submitButton(@NotNull Component label,
                                             @NotNull BiConsumer<Player, Map<String, String>> action) {
            this.buttons.add(new MenuView.Button(label, null, true, 0, action));
            return this;
        }

        public @NotNull AutoDialog build() {
            MenuView view = new MenuView(this.title, this.lines, this.inputs, this.buttons);
            return new AutoDialog(view, dialogOf(view));
        }

        private static @NotNull Dialog dialogOf(@NotNull MenuView view) {
            DialogBase.Builder base = DialogBase.builder(view.title())
                    .canCloseWithEscape(true)
                    .pause(true)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE);
            if (!view.body().isEmpty()) {
                base.body(view.body().stream().<DialogBody>map(DialogBody::plainMessage).toList());
            }
            if (!view.inputs().isEmpty()) {
                base.inputs(view.inputs().stream().map(Builder::dialogInput).toList());
            }
            List<ActionButton> buttons = view.buttons().stream().map(button -> dialogButton(view, button)).toList();
            return InlinedRegistryBuilderProvider.instance().createDialog(factory -> {
                var builder = factory.empty();
                builder.base(base.build());
                // A dialog of several actions must have at least one.
                builder.type(buttons.isEmpty()
                        ? DialogInstancesProvider.instance().notice() : DialogType.multiAction(buttons).build());
            });
        }

        private static @NotNull DialogInput dialogInput(MenuView.@NotNull Input input) {
            return switch (input) {
                case MenuView.Text text -> DialogInput.text(text.key(), text.label())
                        .initial(text.initial()).maxLength(text.maxLength()).labelVisible(true).build();
                case MenuView.Choice choice -> DialogInput.singleOption(choice.key(), choice.label(),
                        choice.options().stream().map(option -> SingleOptionDialogInput.OptionEntry.create(
                                option.id(), option.display(), option.initial())).toList()).build();
            };
        }

        private static @NotNull ActionButton dialogButton(@NotNull MenuView view, MenuView.@NotNull Button button) {
            DialogActionCallback callback = (response, audience) -> {
                if (!(audience instanceof Player player)) {
                    return;
                }
                // What the client sent, by the menu's own inputs: nothing else is read from it.
                Map<String, String> answers = new LinkedHashMap<>(view.initialAnswers());
                for (MenuView.Input input : view.inputs()) {
                    String sent = response.getText(input.key());
                    if (sent != null) {
                        answers.put(input.key(), sent);
                    }
                }
                button.click().accept(player, answers);
            };
            ActionButton.Builder built = ActionButton.builder(button.label())
                    .action(DialogInstancesProvider.instance().register(callback, CLICKS));
            if (button.tooltip() != null) {
                built.tooltip(button.tooltip());
            }
            if (button.width() > 0) {
                built.width(button.width());
            }
            return built.build();
        }
    }
}
