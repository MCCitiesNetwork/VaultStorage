package com.minecraftcitiesnetwork.vaultstorage.menu;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a menu shows and does, without saying how it is drawn. Every menu is built as a Paper
 * dialog and as one of these; a {@link MenuRenderer} draws this one for a player whose client
 * a dialog does not suit.
 *
 * @param body    the lines of text above the inputs and buttons
 * @param inputs  what the player may fill in before pressing a button
 * @param buttons in the order the dialog shows them
 */
public record MenuView(@NotNull Component title, @NotNull List<Component> body, @NotNull List<Input> inputs,
                       @NotNull List<Button> buttons) {

    public MenuView {
        body = List.copyOf(body);
        inputs = List.copyOf(inputs);
        buttons = List.copyOf(buttons);
    }

    /** Something the player fills in. Its value reaches a button's action under {@code key}. */
    public sealed interface Input {
        @NotNull String key();

        @NotNull Component label();

        /** The value an action gets when the player was not asked or changed nothing. */
        @NotNull String initial();
    }

    /** A line of text to type. */
    public record Text(@NotNull String key, @NotNull Component label, @NotNull String initial,
                       int maxLength) implements Input {
    }

    /** One of several options. The value is the chosen option's id. */
    public record Choice(@NotNull String key, @NotNull Component label, @NotNull List<Option> options)
            implements Input {

        public Choice {
            options = List.copyOf(options);
        }

        @Override
        public @NotNull String initial() {
            return this.options.stream().filter(Option::initial).findFirst()
                    .or(() -> this.options.stream().findFirst()).map(Option::id).orElse("");
        }
    }

    public record Option(@NotNull String id, @NotNull Component display, boolean initial) {
    }

    /**
     * @param tooltip     shown while the pointer is over the button, where the client has one;
     *                    null for none. Never the only place something is said.
     * @param readsInputs whether the action uses what the player filled in. A renderer that
     *                    cannot show inputs beside buttons asks for them when this is pressed.
     * @param width       how wide to draw the button where the client lets a menu say, or 0
     * @param click       run on the main thread with every input's value by key
     */
    public record Button(@NotNull Component label, @Nullable Component tooltip, boolean readsInputs, int width,
                         @NotNull BiConsumer<Player, Map<String, String>> click) {
    }

    /** Every input at its initial value: what an action gets when the player was not asked. */
    public @NotNull Map<String, String> initialAnswers() {
        Map<String, String> answers = new LinkedHashMap<>();
        this.inputs.forEach(input -> answers.put(input.key(), input.initial()));
        return answers;
    }
}
