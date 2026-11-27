package com.minecraftcitiesnetwork.vaultstorage.permission;

/** Every permission node. Each is declared in {@code paper-plugin.yml}; {@code PermissionsTest} checks it. */
public final class Permissions {

    /** Staff view in menus, and the parent of every other node. */
    public static final String ADMIN = "vaultstorage.admin";

    public static final String COMMAND_VAULT = "vaultstorage.command.vault";
    public static final String COMMAND_VAULT_OTHERS = "vaultstorage.command.vault.others";
    public static final String COMMAND_OPEN = "vaultstorage.command.open";
    public static final String COMMAND_OPEN_OTHERS = "vaultstorage.command.open.others";
    public static final String COMMAND_ADMIN = "vaultstorage.command.admin";
    public static final String COMMAND_TRANSFER = "vaultstorage.command.transfer";
    public static final String COMMAND_RELOAD = "vaultstorage.command.reload";

    public static final String ACTION_VIEW = "vaultstorage.action.view";
    public static final String ACTION_COPY = "vaultstorage.action.copy";
    public static final String ACTION_EDIT = "vaultstorage.action.edit";
    public static final String ACTION_PLACE = "vaultstorage.action.place";
    public static final String ACTION_CAPTURE = "vaultstorage.action.capture";

    /** Capture and place regardless of region and ownership rules. */
    public static final String BYPASS_REGION = "vaultstorage.bypass.region";

    public static final String SCAN_TELEPORT = "vaultstorage.scan.teleport";

    private Permissions() {
    }
}
