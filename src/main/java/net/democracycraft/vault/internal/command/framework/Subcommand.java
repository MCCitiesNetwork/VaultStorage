package net.democracycraft.vault.internal.command.framework;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public interface Subcommand {
    List<String> names();
    String permission(); // null or empty = vault.user
    String usage();

    default boolean hasPermission(CommandSender sender) {
        String p = permission();
        String eff = (p == null || p.isBlank()) ? "vault.user" : p;
        return sender.hasPermission(eff) || sender.hasPermission("vault.admin");
    }

    void execute(CommandContext ctx);

    default List<String> complete(CommandContext ctx) { return Collections.emptyList(); }
}
