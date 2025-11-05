package net.democracycraft.vault.internal.command.impl;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.internal.command.framework.CommandContext;
import net.democracycraft.vault.internal.command.framework.Subcommand;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** /vault list [mine|all] */
public class ListSubcommand implements Subcommand {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Override public List<String> names() { return List.of("list", "ls"); }
    @Override public String permission() { return "vault.user"; }
    @Override public String usage() { return "list [mine|all]"; }

    @Override
    public void execute(CommandContext ctx) {
        CommandSender sender = ctx.sender();
        boolean mine = true;
        if (ctx.args().length >= 1) {
            String opt = ctx.args()[0].toLowerCase(Locale.ROOT);
            if (opt.equals("all")) mine = false;
        }
        boolean admin = sender.hasPermission("vault.admin");
        Player player = sender instanceof Player p ? p : null;
        UUID filterOwner = mine && player != null ? player.getUniqueId() : null;
        World world = player != null ? player.getWorld() : Bukkit.getWorlds().getFirst();

        // Make effectively-final copies for the async task
        final boolean mineFinal = mine;
        final boolean adminFinal = admin;
        final UUID filterOwnerFinal = filterOwner;
        final World worldFinal = world;
        final CommandSender senderFinal = sender;

        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable() {
            @Override public void run() {
                var vs = plugin.getVaultService();
                List<net.democracycraft.vault.internal.database.entity.VaultEntity> list;
                if (mineFinal && filterOwnerFinal != null) {
                    list = vs.listByOwner(filterOwnerFinal);
                } else if (adminFinal) {
                    list = vs.listInWorld(worldFinal.getUID());
                } else {
                    list = List.of();
                }
                final List<net.democracycraft.vault.internal.database.entity.VaultEntity> result = list;
                new BukkitRunnable() {
                    @Override public void run() {
                        senderFinal.sendMessage("Vaults:");
                        int shown = 0;
                        for (var v : result) {
                            String line = "- " + v.uuid + " | world=" + v.worldUuid + " @ " + v.x + "," + v.y + "," + v.z +
                                    (v.createdAtEpochMillis != null ? (" | " + FMT.format(java.time.Instant.ofEpochMilli(v.createdAtEpochMillis))) : "");
                            senderFinal.sendMessage(line);
                            if (++shown >= 20) { senderFinal.sendMessage("... (more)"); break; }
                        }
                        if (shown == 0) senderFinal.sendMessage("(no vaults)");
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    @Override
    public List<String> complete(CommandContext ctx) {
        if (ctx.args().length <= 1) {
            List<String> opts = new ArrayList<>();
            opts.add("mine");
            if (ctx.sender().hasPermission("vault.admin")) opts.add("all");
            String prefix = ctx.args().length == 0 ? "" : ctx.args()[0];
            return ctx.filter(opts, prefix);
        }
        return List.of();
    }
}
