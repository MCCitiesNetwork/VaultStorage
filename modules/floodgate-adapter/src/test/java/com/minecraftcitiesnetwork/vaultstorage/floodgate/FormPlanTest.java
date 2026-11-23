package com.minecraftcitiesnetwork.vaultstorage.floodgate;

import com.minecraftcitiesnetwork.vaultstorage.menu.MenuView;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** How the plugin's menus are laid out as Bedrock forms. */
class FormPlanTest {

    private static MenuView.Button button(String label, boolean readsInputs) {
        return new MenuView.Button(Component.text(label), readsInputs, (player, answers) -> { });
    }

    private static final MenuView.Text SEARCH = new MenuView.Text("QUERY", Component.text("Search"), "", 255);
    private static final MenuView.Choice ACTION = new MenuView.Choice("ACTION", Component.text("Select action"), List.of(
            new MenuView.Option("view", Component.text("View"), false),
            new MenuView.Option("edit", Component.text("Edit"), true),
            new MenuView.Option("copy", Component.text("Copy"), false)));

    private static MenuView view(List<MenuView.Input> inputs, MenuView.Button... buttons) {
        return new MenuView(Component.text("Menu"), List.of(Component.text("first"), Component.text("second")),
                inputs, List.of(buttons));
    }

    // The vault list: a search box beside a button for each vault.

    @Test
    void aMenuWithInputsAndSeveralButtonsShowsItsButtonsFirst() {
        MenuView list = view(List.of(SEARCH), button("Search", true), button("Vault #1", false), button("Back", false));

        Assertions.assertFalse(FormPlan.asksAtOnce(list));
        Assertions.assertEquals(List.of("Search", "Vault #1", "Back"), FormPlan.buttonLabels(list));
    }

    @Test
    void onlyAButtonThatReadsTheInputsAsksForThem() {
        MenuView list = view(List.of(SEARCH), button("Search", true), button("Vault #1", false));

        Assertions.assertTrue(FormPlan.asksFirst(list, list.buttons().get(0)));
        Assertions.assertFalse(FormPlan.asksFirst(list, list.buttons().get(1)));
    }

    @Test
    void aMenuWhoseOnlyButtonReadsItsInputsShowsTheInputsAtOnce() {
        Assertions.assertTrue(FormPlan.asksAtOnce(view(List.of(SEARCH), button("Search", true))));
    }

    @Test
    void aMenuWithoutInputsNeverAsks() {
        MenuView confirm = view(List.of(), button("Cancel", false), button("Delete", true));

        Assertions.assertFalse(FormPlan.asksAtOnce(confirm));
        Assertions.assertFalse(FormPlan.asksFirst(confirm, confirm.buttons().get(1)));
    }

    @Test
    void theBodyIsOneBlockWithALineForEachPart() {
        Assertions.assertEquals("first\nsecond", FormPlan.content(view(List.of())));
    }

    @Test
    void coloursBecomeTheCodesBedrockShows() {
        Assertions.assertEquals("\u00a7cDelete", FormPlan.text(Component.text("Delete", NamedTextColor.RED)));
    }

    // What the player submitted.

    @Test
    void typedTextAndTheChosenOptionReachTheActionUnderTheirKeys() {
        MenuView menu = view(List.of(SEARCH, ACTION), button("Open", true));

        Assertions.assertEquals(Map.of("QUERY", "alice", "ACTION", "copy"),
                FormPlan.answers(menu, List.of("alice", 2)));
    }

    @Test
    void aChoiceStartsOnTheOptionMarkedInitialAndThatIsWhatAnUnaskedActionGets() {
        MenuView menu = view(List.of(SEARCH, ACTION), button("Place", false));

        Assertions.assertEquals(1, FormPlan.initialOption(ACTION));
        Assertions.assertEquals(List.of("View", "Edit", "Copy"), FormPlan.optionLabels(ACTION));
        Assertions.assertEquals(Map.of("QUERY", "", "ACTION", "edit"), menu.initialAnswers());
    }

    @Test
    void aChoiceWithNoOptionMarkedStartsOnTheFirst() {
        MenuView.Choice unmarked = new MenuView.Choice("K", Component.text("Pick"), List.of(
                new MenuView.Option("a", Component.text("A"), false), new MenuView.Option("b", Component.text("B"), false)));

        Assertions.assertEquals(0, FormPlan.initialOption(unmarked));
        Assertions.assertEquals("a", unmarked.initial());
    }

    @Test
    void anAnswerThatDoesNotFitItsInputLeavesTheInitialValue() {
        MenuView menu = view(List.of(SEARCH, ACTION), button("Open", true));

        // An option that does not exist, and a number where text belongs: a client can send anything.
        Assertions.assertEquals(Map.of("QUERY", "", "ACTION", "edit"), FormPlan.answers(menu, List.of(7, 99)));
        Assertions.assertEquals(Map.of("QUERY", "", "ACTION", "edit"), FormPlan.answers(menu, List.of()));
    }

    @Test
    void textLongerThanTheInputAllowsIsCutToItsLength() {
        MenuView.Text shortInput = new MenuView.Text("QUERY", Component.text("Search"), "", 5);
        MenuView menu = view(List.of(shortInput), button("Search", true));

        Assertions.assertEquals(Map.of("QUERY", "abcde"), FormPlan.answers(menu, List.of("abcdefgh")));
    }

    @Test
    void aSubmittedFormIsReadInTheOrderOfTheInputs() {
        MenuView menu = view(List.of(SEARCH, ACTION), button("Open", true));

        List<Object> values = FormPlan.inOrder(menu, new FormPlan.InputReader() {
            @Override
            public String text(int index) {
                return "text-at-" + index;
            }

            @Override
            public int option(int index) {
                return index;
            }
        });

        Assertions.assertEquals(List.of("text-at-0", 1), values);
    }
}
