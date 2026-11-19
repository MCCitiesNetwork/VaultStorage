package com.minecraftcitiesnetwork.vaultstorage.e2e.realtystub;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import io.github.md5sha256.realty.api.WorldGuardRegion;
import io.github.md5sha256.realty.api.event.RegionBoughtEvent;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/** Fires Realty's "region bought" event on request, so a live test can start a sweep. */
public final class RealtyStubPlugin extends JavaPlugin {

    private static final int ARGUMENTS = 4;

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String @NotNull [] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            return true;
        }
        if (args.length != ARGUMENTS || !args[0].equals("bought")) {
            return false;
        }
        World world = getServer().getWorld(args[1]);
        Player buyer = getServer().getPlayerExact(args[3]);
        RegionManager regions = world == null ? null
                : WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
        ProtectedRegion region = regions == null ? null : regions.getRegion(args[2]);
        if (region == null || buyer == null) {
            sender.sendMessage("realty-stub: no such world, region or player");
            return true;
        }
        new RegionBoughtEvent(new WorldGuardRegion(region, world), buyer.getUniqueId(), null, 0).callEvent();
        sender.sendMessage("realty-stub: " + args[2] + " bought by " + buyer.getName());
        return true;
    }
}
