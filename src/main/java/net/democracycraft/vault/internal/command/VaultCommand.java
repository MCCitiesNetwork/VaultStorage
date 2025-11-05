package net.democracycraft.vault.internal.command;

import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import net.democracycraft.vault.internal.command.impl.CaptureSubcommand;
import net.democracycraft.vault.internal.command.impl.MenuSubcommand;
import net.democracycraft.vault.internal.command.impl.OpenSubcommand;
import net.democracycraft.vault.internal.command.impl.PlaceSubcommand;
import net.democracycraft.vault.internal.command.impl.ListSubcommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * /vault command router. Delegates to subcommands using the simple framework.
 */
public class VaultCommand implements CommandExecutor, TabCompleter {

    private final List<Subcommand> subcommands = List.of(
            new MenuSubcommand(),
            new CaptureSubcommand(),
            new OpenSubcommand(),
            new PlaceSubcommand(),
            new ListSubcommand()
    );

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            // default to menu
            new MenuSubcommand().execute(new CommandContext(sender, label, new String[0]));
            return true;
        }
        String subName = args[0].toLowerCase(Locale.ROOT);
        Subcommand sub = findSub(subName);
        if (sub == null) {
            sender.sendMessage("Unknown subcommand. Try: " + String.join(", ", visibleNames(sender)));
            return true;
        }
        if (!sub.hasPermission(sender)) {
            sender.sendMessage("You don't have permission.");
            return true;
        }
        String[] tail = Arrays.copyOfRange(args, 1, args.length);
        try {
            sub.execute(new CommandContext(sender, label, tail));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ex.getMessage());
            sender.sendMessage("Usage: /" + label + " " + sub.usage());
        } catch (Exception ex) {
            sender.sendMessage("An error occurred.");
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0];
            List<String> names = visibleNames(sender);
            return filter(names, prefix);
        }
        if (args.length >= 2) {
            Subcommand sub = findSub(args[0]);
            if (sub == null) return Collections.emptyList();
            if (!sub.hasPermission(sender)) return Collections.emptyList();
            String[] tail = Arrays.copyOfRange(args, 1, args.length);
            return sub.complete(new CommandContext(sender, alias, tail));
        }
        return Collections.emptyList();
    }

    private Subcommand findSub(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (Subcommand s : subcommands) {
            for (String alias : s.names()) {
                if (alias.equalsIgnoreCase(n)) return s;
            }
        }
        return null;
    }

    private List<String> visibleNames(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (Subcommand s : subcommands) {
            if (s.hasPermission(sender)) names.add(s.names().get(0));
        }
        return names;
    }

    private List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).limit(50).toList();
    }
}
