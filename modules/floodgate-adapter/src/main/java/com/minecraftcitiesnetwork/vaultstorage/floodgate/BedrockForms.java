package com.minecraftcitiesnetwork.vaultstorage.floodgate;

import com.minecraftcitiesnetwork.vaultstorage.menu.MenuRenderer;
import com.minecraftcitiesnetwork.vaultstorage.menu.MenuView;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.CustomFormResponse;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;

/**
 * Draws the plugin's menus as Bedrock forms for players who joined through Floodgate. A Java
 * dialog reaches a Bedrock client only as whatever Geyser makes of it; a form is the client's
 * own menu. See {@link FormPlan} for the layout.
 *
 * <p>Names Floodgate's types, so it is built only when Floodgate is present.
 */
public final class BedrockForms implements MenuRenderer {

    private final @NotNull FloodgateApi floodgate;
    private final @NotNull Executor mainThread;
    private final @NotNull Logger logger;

    private BedrockForms(@NotNull FloodgateApi floodgate, @NotNull Executor mainThread, @NotNull Logger logger) {
        this.floodgate = floodgate;
        this.mainThread = mainThread;
        this.logger = logger;
    }

    /** @param mainThread where a button's action runs: a form's answer arrives on another thread */
    public static @NotNull MenuRenderer create(@NotNull Executor mainThread, @NotNull Logger logger) {
        return new BedrockForms(FloodgateApi.getInstance(), mainThread, logger);
    }

    @Override
    public boolean show(@NotNull Player player, @NotNull MenuView view) {
        if (!this.floodgate.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }
        if (FormPlan.asksAtOnce(view)) {
            // Closing the inputs leaves the menu: there is nothing else on it to go back to.
            return ask(player, view, view.buttons().getFirst(), () -> { });
        }
        return showButtons(player, view);
    }

    private boolean showButtons(@NotNull Player player, @NotNull MenuView view) {
        SimpleForm.Builder form = SimpleForm.builder()
                .title(FormPlan.text(view.title()))
                .content(FormPlan.content(view));
        FormPlan.buttonLabels(view).forEach(form::button);
        form.validResultHandler(response -> {
            int clicked = response.clickedButtonId();
            if (clicked < 0 || clicked >= view.buttons().size()) {
                return;
            }
            MenuView.Button button = view.buttons().get(clicked);
            this.mainThread.execute(() -> {
                if (!player.isOnline()) {
                    return;
                }
                if (FormPlan.asksFirst(view, button)) {
                    // Closing the inputs without submitting goes back to the buttons.
                    ask(player, view, button, () -> showButtons(player, view));
                } else {
                    click(player, button, view.initialAnswers());
                }
            });
        });
        return this.floodgate.sendForm(player.getUniqueId(), form);
    }

    /** Shows the menu's inputs; submitting them runs the button's action with what was filled in. */
    private boolean ask(@NotNull Player player, @NotNull MenuView view, MenuView.@NotNull Button button,
                        @NotNull Runnable whenClosed) {
        CustomForm.Builder form = CustomForm.builder().title(FormPlan.text(button.label()));
        String content = FormPlan.content(view);
        if (!content.isBlank()) {
            form.label(content);
        }
        // A label is a component of the form too, so the inputs start one further on.
        int offset = content.isBlank() ? 0 : 1;
        for (MenuView.Input input : view.inputs()) {
            switch (input) {
                case MenuView.Text text -> form.input(FormPlan.text(text.label()), "", text.initial());
                case MenuView.Choice choice -> form.dropdown(FormPlan.text(choice.label()),
                        FormPlan.optionLabels(choice), FormPlan.initialOption(choice));
            }
        }
        form.validResultHandler(response -> {
            Map<String, String> answers;
            try {
                answers = FormPlan.answers(view, FormPlan.inOrder(view, reader(response, offset)));
            } catch (RuntimeException malformed) {
                // An answer of the wrong shape for the form that was sent: treated as not answered.
                this.logger.log(Level.WARNING, player.getName() + " answered a form with something it did not ask for.",
                        malformed);
                answers = view.initialAnswers();
            }
            Map<String, String> filledIn = answers;
            this.mainThread.execute(() -> {
                if (player.isOnline()) {
                    click(player, button, filledIn);
                }
            });
        });
        form.closedOrInvalidResultHandler(() -> this.mainThread.execute(() -> {
            if (player.isOnline()) {
                whenClosed.run();
            }
        }));
        return this.floodgate.sendForm(player.getUniqueId(), form);
    }

    private static FormPlan.@NotNull InputReader reader(@NotNull CustomFormResponse response, int offset) {
        return new FormPlan.InputReader() {
            @Override
            public @NotNull String text(int index) {
                String typed = response.asInput(index + offset);
                return typed == null ? "" : typed;
            }

            @Override
            public int option(int index) {
                return response.asDropdown(index + offset);
            }
        };
    }

    private void click(@NotNull Player player, MenuView.@NotNull Button button, @NotNull Map<String, String> answers) {
        try {
            button.click().accept(player, answers);
        } catch (RuntimeException ex) {
            this.logger.log(Level.SEVERE, "A menu button failed for " + player.getName() + ".", ex);
        }
    }
}
