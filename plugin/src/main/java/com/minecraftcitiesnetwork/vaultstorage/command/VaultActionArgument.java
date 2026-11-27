package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.vaultstorage.vault.VaultAction;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** The action word of {@code /vault open}: view, copy or edit. */
public final class VaultActionArgument {

    private VaultActionArgument() {
    }

    public static @NotNull Optional<VaultAction> parse(@NotNull String word) {
        String folded = word.toUpperCase(Locale.ROOT);
        return Arrays.stream(VaultAction.values()).filter(action -> action.name().equals(folded)).findFirst();
    }

    /** The words a player may type, lower case. */
    public static @NotNull List<String> words() {
        return Arrays.stream(VaultAction.values()).map(action -> action.name().toLowerCase(Locale.ROOT)).toList();
    }
}
