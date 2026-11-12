package net.democracycraft.vault.internal.security;

import net.democracycraft.vault.internal.ui.VaultAction;
import org.bukkit.command.CommandSender;

/**
 * Centralized permission nodes used by the VaultStorage plugin.
 * <p>
 * Provides a small set of helpers to check permissions consistently across the codebase.
 * All checks implicitly allow users with the ADMIN permission as an override.
 */
public enum VaultPermission {
    /** Basic user permission for standard commands. */
    USER("vaultstorage.user"),
    /** Admin override for all plugin actions. */
    ADMIN("vaultstorage.admin"),
    /** Permission to view vault contents in a virtual inventory. */
    ACTION_VIEW("vaultstorage.action.view"),
    /** Permission to edit vault contents in a virtual inventory. */
    ACTION_EDIT("vaultstorage.action.edit"),
    /** Permission to copy items from a vault to the player inventory. */
    ACTION_COPY("vaultstorage.action.copy"),
    /** Permission to place back the original block and restore its contents. */
    ACTION_PLACE("vaultstorage.action.place"),
    /** Permission to override region membership when capturing/placing a vault block. */
    ACTION_PLACE_OVERRIDE("vaultstorage.admin.override"),
    /** Permission to capture a container into a vault. */
    ACTION_CAPTURE("vaultstorage.action.capture");

    private final String node;

    VaultPermission(String node) { this.node = node; }

    /**
     * Returns the Bukkit permission node string (as declared in plugin.yml).
     */
    public String node() { return node; }

    /**
     * Checks if the sender has this permission or the {@link #ADMIN} override.
     */
    public boolean has(CommandSender sender) { return has(sender, this); }

    /**
     * Checks if the sender has the given permission or the {@link #ADMIN} override.
     */
    public static boolean has(CommandSender sender, VaultPermission permission) {
        return sender.hasPermission(permission.node) || sender.hasPermission(ADMIN.node);
    }

    /**
     * Alias of {@link #has(CommandSender, VaultPermission)} with a more explicit name.
     */
    public static boolean hasPermission(CommandSender sender, VaultPermission permission) {
        return has(sender, permission);
    }

    /**
     * Checks if the sender has the given permission node string or the {@link #ADMIN} override.
     * Use this when you only have a node as a string.
     */
    public static boolean has(CommandSender sender, String node) {
        return sender.hasPermission(node) || sender.hasPermission(ADMIN.node);
    }

    /**
     * Alias of {@link #has(CommandSender, String)} with a more explicit name.
     */
    public static boolean hasPermission(CommandSender sender, String node) {
        return has(sender, node);
    }

    /**
     * Checks that the sender has all of the given permissions (ADMIN also grants).
     */
    public static boolean all(CommandSender sender, VaultPermission... permissions) {
        for (VaultPermission p : permissions) {
            if (!has(sender, p)) return false;
        }
        return true;
    }

    /**
     * Checks that the sender has at least one of the given permissions (ADMIN also grants).
     */
    public static boolean any(CommandSender sender, VaultPermission... permissions) {
        for (VaultPermission p : permissions) {
            if (has(sender, p)) return true;
        }
        return false;
    }

    /**
     * Resolves the required permission for a {@link VaultAction}.
     */
    public static VaultPermission from(VaultAction action) {
        return switch (action) {
            case VIEW -> ACTION_VIEW;
            case EDIT -> ACTION_EDIT;
            case COPY -> ACTION_COPY;
        };
    }
}
