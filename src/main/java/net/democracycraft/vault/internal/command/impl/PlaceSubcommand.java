package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;

import java.util.List;

/** /vault place <vaultId> */
public class PlaceSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("place", "put"); }
    @Override public String permission() { return "vault.action.place"; }
    @Override public String usage() { return "place <vaultId>"; }

    @Override
    public void execute(CommandContext ctx) {
        ctx.sender().sendMessage("Placement is not supported with the current storage backend.");
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        return List.of();
    }
}
