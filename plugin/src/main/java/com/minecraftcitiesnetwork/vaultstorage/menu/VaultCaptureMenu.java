package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** The first menu: start capture mode, scan a region, or browse vaults. */
public final class VaultCaptureMenu extends ParentMenuImp {

    private final @NotNull VaultUIContext uiContext;

    /** The player's own vaults. */
    public VaultCaptureMenu(@NotNull VaultStoragePlugin plugin, @NotNull Player player) {
        this(plugin, player, VaultUIContext.self(player.getUniqueId()));
    }

    public VaultCaptureMenu(@NotNull VaultStoragePlugin plugin, @NotNull Player player, @NotNull VaultUIContext context) {
        super(plugin, player);
        this.uiContext = context;
    }

    @Override
    protected @NotNull AutoDialog build() {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("%player%", player.getName());
        if (uiContext.filterOwner() != null) {
            placeholders.put("%filterOwner%", uiContext.filterOwner().toString());
        } else if (uiContext.admin()) {
            placeholders.put("%filterOwner%", "ALL");
        }
        AutoDialog.Builder builder = AutoDialog.builder()
                .title(plugin.text().of(MessageKeys.CAPTURE_MENU_TITLE, placeholders));

        // A player who may not vault containers is here for their own vaults: they are told so,
        // and are not offered a button that would only refuse them.
        if (player.hasPermission(Permissions.ACTION_CAPTURE)) {
            builder.line(plugin.text().of(MessageKeys.CAPTURE_MENU_INSTRUCTION, placeholders));
            builder.line(plugin.text().of(MessageKeys.CAPTURE_MENU_CANCEL_HINT, placeholders));
            permissionButton(builder, Permissions.ACTION_CAPTURE,
                    plugin.text().of(MessageKeys.CAPTURE_MENU_START_BTN, placeholders), actor -> {
                        plugin.captureService().startCaptureSession(actor);
                        actor.closeDialog();
                    });
        } else {
            builder.line(plugin.text().of(MessageKeys.CAPTURE_MENU_INSTRUCTION_OWNER, placeholders));
        }
        builder.button(plugin.text().of(MessageKeys.CAPTURE_MENU_OPEN_SCAN_BTN, placeholders),
                actor -> new VaultScanMenu(actor, this, uiContext).open());
        builder.button(plugin.text().of(MessageKeys.CAPTURE_MENU_BROWSE_BTN, placeholders),
                actor -> new VaultListMenu(actor, this, uiContext, "").open());
        builder.button(plugin.text().of(MessageKeys.CAPTURE_MENU_CLOSE_BTN, placeholders), actor -> { });
        return builder.build();
    }
}
