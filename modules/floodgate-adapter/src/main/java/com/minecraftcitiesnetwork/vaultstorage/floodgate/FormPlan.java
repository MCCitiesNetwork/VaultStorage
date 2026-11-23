package com.minecraftcitiesnetwork.vaultstorage.floodgate;

import com.minecraftcitiesnetwork.vaultstorage.menu.MenuView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * How a menu is laid out as Bedrock forms. Bedrock has a form of buttons and a form of inputs
 * with one submit button, and none with both, so a menu with inputs becomes two steps: the
 * buttons first, and the inputs when a button that reads them is pressed.
 *
 * <p>Names no Floodgate type, so the layout is tested without Floodgate.
 */
final class FormPlan {

    /** Bedrock shows section-sign colour codes, not components. */
    private static final LegacyComponentSerializer TEXT = LegacyComponentSerializer.legacySection();

    private FormPlan() {
    }

    /**
     * Whether the menu is shown as its inputs at once. So when it has inputs and its only
     * button reads them: a menu of one button that only opens the inputs would be a wasted step.
     */
    static boolean asksAtOnce(@NotNull MenuView view) {
        return !view.inputs().isEmpty() && view.buttons().size() == 1 && view.buttons().getFirst().readsInputs();
    }

    /** Whether pressing the button opens the inputs before its action runs. */
    static boolean asksFirst(@NotNull MenuView view, MenuView.@NotNull Button button) {
        return button.readsInputs() && !view.inputs().isEmpty();
    }

    static @NotNull String text(@NotNull Component component) {
        return TEXT.serialize(component);
    }

    /** The menu's body as one block of text, a line for each part. */
    static @NotNull String content(@NotNull MenuView view) {
        return String.join("\n", view.body().stream().map(FormPlan::text).toList());
    }

    static @NotNull List<String> buttonLabels(@NotNull MenuView view) {
        return view.buttons().stream().map(button -> text(button.label())).toList();
    }

    /** What a choice offers, in order, as the player reads it. */
    static @NotNull List<String> optionLabels(MenuView.@NotNull Choice choice) {
        return choice.options().stream().map(option -> text(option.display())).toList();
    }

    /** Which option a choice starts on. */
    static int initialOption(MenuView.@NotNull Choice choice) {
        for (int index = 0; index < choice.options().size(); index++) {
            if (choice.options().get(index).initial()) {
                return index;
            }
        }
        return 0;
    }

    /**
     * The inputs' values by key, from what the player submitted.
     *
     * @param submitted one entry for each input, in order: the text typed, or the index of the
     *                  option chosen. An entry that is missing or does not fit its input leaves
     *                  that input at its initial value.
     */
    static @NotNull Map<String, String> answers(@NotNull MenuView view, @NotNull List<Object> submitted) {
        Map<String, String> answers = new LinkedHashMap<>(view.initialAnswers());
        for (int index = 0; index < view.inputs().size() && index < submitted.size(); index++) {
            Object value = submitted.get(index);
            switch (view.inputs().get(index)) {
                case MenuView.Text text -> {
                    if (value instanceof String typed) {
                        answers.put(text.key(), typed.length() > text.maxLength() ? typed.substring(0, text.maxLength()) : typed);
                    }
                }
                case MenuView.Choice choice -> {
                    if (value instanceof Integer chosen && chosen >= 0 && chosen < choice.options().size()) {
                        answers.put(choice.key(), choice.options().get(chosen).id());
                    }
                }
            }
        }
        return answers;
    }

    /** The values a submitted form holds for these inputs, read by the caller in order. */
    static @NotNull List<Object> inOrder(@NotNull MenuView view, @NotNull InputReader reader) {
        List<Object> values = new ArrayList<>();
        for (int index = 0; index < view.inputs().size(); index++) {
            values.add(switch (view.inputs().get(index)) {
                case MenuView.Text text -> reader.text(index);
                case MenuView.Choice choice -> reader.option(index);
            });
        }
        return values;
    }

    /** Reads a submitted form by the position of each input. */
    interface InputReader {
        @NotNull String text(int index);

        int option(int index);
    }
}
