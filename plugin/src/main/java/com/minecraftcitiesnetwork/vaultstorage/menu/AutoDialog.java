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
import io.papermc.paper.registry.data.dialog.body.PlainMessageDialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
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

/**
 * A menu as built: the Paper dialog, and the same menu as a {@link MenuView} for a
 * {@link MenuRenderer} to draw another way.
 */
public final class AutoDialog {

    private final @NotNull Dialog dialog;
    private final @NotNull MenuView view;

    private AutoDialog(@NotNull Dialog dialog, @NotNull MenuView view) {
        this.dialog = dialog;
        this.view = view;
    }

    public @NotNull Dialog dialog() {
        return this.dialog;
    }

    public @NotNull MenuView view() {
        return this.view;
    }

    public static @NotNull Builder builder(@NotNull ParentMenu parentMenu) {
        return new Builder(parentMenu);
    }

    public static final class Builder {

        private static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);
        private static final int DEFAULT_USES = 10;

        private final ParentMenu parentMenu;

        private Builder(@NotNull ParentMenu parentMenu) {
            this.parentMenu = parentMenu;
        }

        private Component title = Component.text("Dialog");
        private boolean canCloseWithEscape = true;
        private DialogBase.DialogAfterAction afterAction = DialogBase.DialogAfterAction.CLOSE;

        private final List<DialogBody> body = new ArrayList<>();
        private final List<DialogInput> inputs = new ArrayList<>();
        private final List<ActionButton> buttons = new ArrayList<>();
        private final List<MenuView.Button> viewButtons = new ArrayList<>();

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

        public @NotNull Builder button(@NotNull Component label, @NotNull Consumer<Context> handler) {
            return button(label, null, DEFAULT_LIFETIME, DEFAULT_USES, handler);
        }

        public @NotNull Builder button(@NotNull Component label, @Nullable Component tooltip, @NotNull Duration expireAfter,
                                       int maxUses, @NotNull Consumer<Context> handler) {
            return add(label, tooltip, expireAfter, maxUses, 0, false,
                    (player, response) -> handler.accept(new Context(player, player, response)));
        }

        public @NotNull Builder sizableButton(@NotNull Component label, @NotNull Consumer<Context> handler,
                                              @Range(from = 1, to = 1024) int width) {
            return sizableButton(label, null, DEFAULT_LIFETIME, DEFAULT_USES, handler, width);
        }

        public @NotNull Builder sizableButton(@NotNull Component label, @Nullable Component tooltip,
                                              @NotNull Duration expireAfter, int maxUses, @NotNull Consumer<Context> handler,
                                              @Range(from = 1, to = 1024) int width) {
            return add(label, tooltip, expireAfter, maxUses, width, false,
                    (player, response) -> handler.accept(new Context(player, player, response)));
        }

        /** A button whose action does not use the menu's inputs. */
        public @NotNull Builder buttonWithPlayer(@NotNull Component label, @Nullable Component tooltip,
                                                 @NotNull Duration expireAfter, int maxUses,
                                                 @NotNull BiConsumer<Player, DialogResponseView> handler) {
            return add(label, tooltip, expireAfter, maxUses, 0, false, handler);
        }

        /**
         * A button whose action reads the menu's inputs. Said apart from the others because a
         * client that cannot show inputs beside buttons asks for them when this one is pressed.
         */
        public @NotNull Builder submitButton(@NotNull Component label, @Nullable Component tooltip,
                                             @NotNull Duration expireAfter, int maxUses,
                                             @NotNull BiConsumer<Player, DialogResponseView> handler) {
            return add(label, tooltip, expireAfter, maxUses, 0, true, handler);
        }

        /** @param width the button's width in the dialog, or 0 for the default */
        private @NotNull Builder add(@NotNull Component label, @Nullable Component tooltip, @NotNull Duration expireAfter,
                                     int maxUses, int width, boolean readsInputs,
                                     @NotNull BiConsumer<Player, DialogResponseView> handler) {
            Objects.requireNonNull(label);
            ClickCallback.Options options = ClickCallback.Options.builder()
                    .lifetime(expireAfter)
                    .uses(maxUses)
                    .build();
            DialogActionCallback callback = (response, audience) -> {
                if (audience instanceof Player player) {
                    handler.accept(player, response);
                }
            };
            DialogAction action = DialogInstancesProvider.instance().register(callback, options);
            ActionButton.Builder button = ActionButton.builder(label).tooltip(tooltip).action(action);
            if (width > 0) {
                button.width(width);
            }
            this.buttons.add(button.build());
            this.viewButtons.add(new MenuView.Button(label, readsInputs,
                    (player, answers) -> handler.accept(player, new MenuAnswers(answers))));
            return this;
        }

        public @NotNull AutoDialog build() {
            DialogBase.Builder baseBuilder = DialogBase.builder(title)
                    .canCloseWithEscape(canCloseWithEscape)
                    .pause(true)
                    .afterAction(afterAction);
            if (!body.isEmpty()) baseBuilder.body(body);
            if (!inputs.isEmpty()) baseBuilder.inputs(inputs);

            Dialog dialog = InlinedRegistryBuilderProvider.instance().createDialog(factory -> {
                var builder = factory.empty();
                builder.base(baseBuilder.build());
                if (!buttons.isEmpty()) {
                    builder.type(DialogType.multiAction(buttons).build());
                } else {
                    builder.type(DialogInstancesProvider.instance().notice());
                }
            });
            return new AutoDialog(dialog, view());
        }

        /** The same menu, read back from what the dialog was built from. */
        private @NotNull MenuView view() {
            List<Component> lines = new ArrayList<>();
            for (DialogBody element : this.body) {
                if (element instanceof PlainMessageDialogBody message) {
                    lines.add(message.contents());
                }
            }
            List<MenuView.Input> asked = new ArrayList<>();
            for (DialogInput input : this.inputs) {
                if (input instanceof TextDialogInput text) {
                    asked.add(new MenuView.Text(text.key(), text.label(), text.initial(), text.maxLength()));
                } else if (input instanceof SingleOptionDialogInput choice) {
                    asked.add(new MenuView.Choice(choice.key(), choice.label(), choice.entries().stream()
                            .map(entry -> new MenuView.Option(entry.id(),
                                    // An option without a label shows its id, as the dialog does.
                                    entry.display() == null ? Component.text(entry.id()) : entry.display(),
                                    entry.initial())).toList()));
                }
            }
            return new MenuView(this.title, lines, asked, this.viewButtons);
        }
    }

    public record Context(@NotNull Player player, @NotNull Audience audience, @NotNull DialogResponseView response) {
    }
}
