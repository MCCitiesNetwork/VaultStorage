package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Subcommand: /vault capture
 * Starts the centralized interactive capture session (action bar + dynamic listener) immediately.
 */
public class CaptureSubcommand implements Subcommand {
    @Override public List<String> names() {
        return List.of("capture", "cap");
    }
    @Override public @NotNull VaultPermission permission() {
        return VaultPermission.ACTION_CAPTURE;
    }
    @Override public String usage() {
        return "capture";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!ctx.isPlayer()) { ctx.sender().sendMessage("Only players can use this."); return; }
        Player player = ctx.asPlayer();
        if (!VaultPermission.ACTION_CAPTURE.has(player)) {
            player.sendMessage("You don't have permission to capture blocks.");
            return;
        }
        VaultCaptureService captureService = net.democracycraft.vault.VaultStoragePlugin.getInstance().getCaptureService();
        captureService.startCaptureSession(player); // opens session directly
        player.sendMessage("Capture session started. Left-click to cancel.");
    }
}
