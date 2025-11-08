package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.ui.VaultCaptureMenu;
import net.democracycraft.vault.internal.ui.VaultPlacementMenu;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/** /vault place <vaultId> */
public class PlaceSubcommand implements Subcommand {
    @Override public List<String> names() { return List.of("place", "put"); }
    @Override public @NotNull VaultPermission permission() { return VaultPermission.ACTION_PLACE; }
    @Override public String usage() { return "place <vaultId>"; }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.isPlayer()) { ctx.sender().sendMessage("Only players can use this."); return; }
        String idStr = ctx.require(0, "vaultId");
        UUID id;
        try { id = UUID.fromString(idStr); } catch (IllegalArgumentException ex) { ctx.sender().sendMessage("Invalid vault id."); return; }
        Player actor = ctx.asPlayer();
        // Use a simple parent menu (capture menu) as parent context
        new VaultPlacementMenu(actor, new VaultCaptureMenu(actor), id).open();
    }

    @Override
    public List<String> complete(CommandContext ctx) { return List.of(); }
}
