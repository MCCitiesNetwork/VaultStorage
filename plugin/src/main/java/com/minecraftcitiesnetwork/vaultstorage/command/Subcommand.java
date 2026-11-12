package com.minecraftcitiesnetwork.vaultstorage.command;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;
import com.minecraftcitiesnetwork.vaultstorage.permission.VaultPermission;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a concrete subcommand of /vault.
 * Implementations MUST return a non-null {@link VaultPermission} from permission().
 */
public interface Subcommand {
    List<String> names();
    @NotNull VaultPermission permission(); // MUST be non-null
    String usage();

    default boolean hasPermission(CommandSender sender) {
        VaultPermission perm = permission();
        return VaultPermission.has(sender, perm);
    }

    void execute(CommandContext ctx);

    default List<String> complete(CommandContext ctx) { return Collections.emptyList(); }
}
