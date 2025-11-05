package net.democracycraft.vault.internal.command.framework;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public record CommandContext(
        CommandSender sender,
        String label,
        String[] args
) {

    public CommandContext next() {
        if (args.length <= 1) return new CommandContext(sender, label, new String[0]);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return new CommandContext(sender, label, rest);
    }

    public boolean isPlayer() {
        return sender instanceof Player;
    }

    public Player asPlayer() {
        return (Player) sender;
    }

    public String require(int index, String name) {
        if (index >= args.length) throw new IllegalArgumentException(name + " is required");
        return args[index];
    }

    public int requireInt(int index, String name) {
        return parseInt(require(index, name), name);
    }

    public int parseInt(String s, String name) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    public List<String> filter(List<String> list, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return list.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(p)).limit(50).toList();
    }

    public void usage(String usage) {
        sender.sendMessage("Usage: /" + label + " " + usage);
    }
}
