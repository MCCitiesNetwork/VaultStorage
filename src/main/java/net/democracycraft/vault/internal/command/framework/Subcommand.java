package net.democracycraft.vault.internal.command.framework;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;
import net.democracycraft.vault.internal.security.VaultPermission;
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
