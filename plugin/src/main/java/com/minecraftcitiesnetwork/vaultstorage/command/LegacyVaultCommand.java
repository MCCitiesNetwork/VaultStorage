package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Bridges the hand-rolled subcommand router onto Paper's command registration, which a
 * paper-plugin.yml plugin must use. Deleted when the commands move to Brigadier.
 */
@SuppressWarnings("UnstableApiUsage")
public final class LegacyVaultCommand implements BasicCommand {

    private static final String LABEL = "vault";

    private final @NotNull VaultCommand delegate;

    public LegacyVaultCommand(@NotNull VaultCommand delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String @NotNull [] args) {
        // The router never reads its Command parameter.
        this.delegate.onCommand(source.getSender(), null, LABEL, args);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String @NotNull [] args) {
        // Bukkit passes one empty argument when nothing is typed yet; BasicCommand passes none.
        String[] typed = args.length == 0 ? new String[] {""} : args;
        List<String> suggestions = this.delegate.onTabComplete(source.getSender(), null, LABEL, typed);
        return suggestions == null ? List.of() : suggestions;
    }

    @Override
    public @NotNull String permission() {
        return VaultPermission.USER.node();
    }
}
