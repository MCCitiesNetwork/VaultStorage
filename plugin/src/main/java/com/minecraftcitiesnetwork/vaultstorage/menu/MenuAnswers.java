package com.minecraftcitiesnetwork.vaultstorage.menu;

import io.papermc.paper.dialog.DialogResponseView;
import java.util.Map;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A menu's answers that did not come from a dialog, in the shape a dialog's answers have, so a
 * button's action reads both the same way.
 */
final class MenuAnswers implements DialogResponseView {

    private final @NotNull Map<String, String> answers;

    MenuAnswers(@NotNull Map<String, String> answers) {
        this.answers = Map.copyOf(answers);
    }

    @Override
    public @NotNull BinaryTagHolder payload() {
        return BinaryTagHolder.binaryTagHolder("{}");
    }

    @Override
    public @Nullable String getText(@NotNull String key) {
        return this.answers.get(key);
    }

    @Override
    public @Nullable Boolean getBoolean(@NotNull String key) {
        String value = this.answers.get(key);
        return value == null ? null : Boolean.valueOf(value);
    }

    @Override
    public @Nullable Float getFloat(@NotNull String key) {
        String value = this.answers.get(key);
        try {
            return value == null ? null : Float.valueOf(value);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
