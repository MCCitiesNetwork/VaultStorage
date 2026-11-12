package com.minecraftcitiesnetwork.vaultstorage.command;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.config.Text;
import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** /vault reload: re-reads settings.yml, messages.yml and the reloadable modules. */
public final class ReloadSubcommand implements Subcommand {

    @Override
    public List<String> names() {
        return List.of("reload");
    }

    @Override
    public @NotNull VaultPermission permission() {
        return VaultPermission.ADMIN;
    }

    @Override
    public String usage() {
        return "reload";
    }

    @Override
    public void execute(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        VaultStoragePlugin plugin = VaultStoragePlugin.getInstance();
        // The cause of a failure is in the log; the sender is told only which way it went.
        plugin.reload().handleAsync((done, failure) -> {
            sender.sendMessage(Text.of(failure == null
                    ? MessageKeys.COMMAND_RELOAD_DONE : MessageKeys.COMMAND_RELOAD_FAILED));
            return null;
        }, plugin.getServer().getScheduler().getMainThreadExecutor(plugin));
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        return List.of();
    }
}
