package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.config.Text;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.menu.AutoDialog;
import com.minecraftcitiesnetwork.vaultstorage.permission.Permissions;
import com.minecraftcitiesnetwork.vaultstorage.capture.VaultCaptureService;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Vault capture UI shown when the command is executed.
 * <p>
 * Authorization policy (overlapping-region aware) applies to any block, not just containers:
 * <ul>
 *   <li>Override permission: may always capture.</li>
 *   <li>Region participant (owner or member): may capture only if the block has a Bolt owner different from themselves AND that owner is not a participant of ANY overlapping region.</li>
 *   <li>Non-participant (not owner nor member in overlapping regions): may capture only their own Bolt-owned blocks.</li>
 *   <li>Unprotected blocks (no Bolt owner) are capturable only with override.</li>
 * </ul>
 * Action bar reflects vaultability in real time using the same logic. Session remains active until the player cancels with a left-click.
 */
public class VaultCaptureMenu extends ParentMenuImp {



    /** Ensures the YAML file for this menu exists by creating defaults if missing. */

    private final VaultUIContext uiContext;

    /** Existing constructor kept: self-filter context */
    public VaultCaptureMenu(Player player) {
        this(player, VaultUIContext.self(player.getUniqueId()));
    }

    /** New constructor accepting UI context (admin or filtered). */
    public VaultCaptureMenu(Player player, VaultUIContext context) {
        super(player, "vault_capture");
        this.uiContext = context;
        setDialog(build());
    }

    /**
     * Builds the dialog structure and associated actions.
     * @return the constructed dialog instance
     */
    private Dialog build() {
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> phSelf = new java.util.HashMap<>();
        phSelf.put("%player%", getPlayer().getName());
        if (uiContext.filterOwner() != null) {
            phSelf.put("%filterOwner%", uiContext.filterOwner().toString());
        } else if (uiContext.admin()) {
            phSelf.put("%filterOwner%", "ALL");
        }
        builder.title(Text.of(MessageKeys.CAPTURE_MENU_TITLE, phSelf));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.CAPTURE_MENU_INSTRUCTION, phSelf)));
        builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.CAPTURE_MENU_CANCEL_HINT, phSelf)));

        // Start capture mode now delegates fully to VaultCaptureService
        builder.button(Text.of(MessageKeys.CAPTURE_MENU_START_BTN, phSelf), ctx -> {
            Player actor = ctx.player();
            if (!actor.hasPermission(Permissions.ACTION_CAPTURE)) {
                actor.sendMessage(Text.of(MessageKeys.CAPTURE_NO_PERMISSION));
                return;
            }
            VaultCaptureService svc = VaultStoragePlugin.getInstance().getCaptureService();
            svc.startCaptureSession(actor); // centralized session (action bar + listener)
            actor.closeDialog();
        });

        builder.button(Text.of(MessageKeys.CAPTURE_MENU_OPEN_SCAN_BTN, phSelf), ctx -> new VaultScanMenu(ctx.player(), this, uiContext).open());
        builder.button(Text.of(MessageKeys.CAPTURE_MENU_BROWSE_BTN, phSelf), ctx -> new VaultListMenu(ctx.player(), this, uiContext, "").open());
        builder.button(Text.of(MessageKeys.CAPTURE_MENU_CLOSE_BTN, phSelf), ctx -> {});
        return builder.build();
    }

    /** Public accessor for empty capture message (used by command subcaptures). */

    @Override
    public void open() {
        setDialog(build());
        super.open();
    }
}
