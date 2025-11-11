package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.security.VaultPermission;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.entity.Player;

import java.util.*;

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

    /** Configurable menu texts. */
    public static class Config implements Dto, java.io.Serializable {
        /** Dialog title. Supports %player% placeholder. */
        public String title = "<gold><bold>Vault Capture</bold></gold>";
        /** Instruction line explaining how to start capture. Supports %player%. */
        public String instruction = "<gray>Click any block to convert it into a Vault. Containers with items will be removed; non-containers or empty containers only remove Bolt protection.</gray>";
        /** Instruction line explaining how to cancel. Supports %player%. */
        public String cancelHint = "<gray>Left-click anywhere to cancel.</gray>";
        /** Button to start capture mode. Supports %player%. */
        public String startBtn = "<green><bold>Start Capture</bold></green>";
        /** Button to browse vaults. Supports %player%. */
        public String browseBtn = "<green>Browse Vaults</green>";
        /** Button to close dialog. Supports %player%. */
        public String closeBtn = "<red><bold>Close</bold></red>";

        public String emptyCaptureSkipped = "Entity unlocked";
        /** Button to open the scan menu. */
        public String openScanBtn = "<yellow>Scan Region</yellow>";
    }

    private static final String HEADER = String.join("\n",
            "VaultCaptureMenu configuration.",
            "All strings accept MiniMessage or plain text.",
            "General placeholders:",
            "- %player% -> current player name",
            "- %owner%  -> block Bolt owner name/UUID or 'unprotected' text",
            "- %vaultable% -> yes/no value (actionBarVaultableYes / actionBarVaultableNo)",
            "- %reasonSegment% -> preformatted segment starting with prefix (blank if allowed)",
            "- %reason% -> resolved human readable reason text (inside reason segment template)",
            "- %reasonCode% -> enum code (OWNER_SELF_IN_REGION, CONTAINER_OWNER_IN_OVERLAP, NOT_INVOLVED_NOT_OWNER, UNPROTECTED_NO_OVERRIDE, NOT_IN_REGION, ALLOWED)",
            "- %regions% -> comma separated region ids overlapping target (action bar only)",
            "Capture flow placeholders:",
            "- Used inside reason* fields: %player% %owner% %regions% %reasonCode%",
            "Customization notes:",
            "- Set actionBarReasonSegmentTemplate to blank to hide reasons entirely.",
            "- Provide your own coloring/formatting per reason* field.",
            "- Deprecated fields retained for backward compatibility: reasonAllowedBlank, reasonActorMemberBlockedLegacy." );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultCaptureMenu", DataFolder.MENUS, HEADER);
    private static Config cfg() { return YML.loadOrCreate(Config::new); }
    /** Ensures the YAML file for this menu exists by creating defaults if missing. */
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }

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
        Config cfg = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> phSelf = new java.util.HashMap<>();
        phSelf.put("%player%", getPlayer().getName());
        if (uiContext.filterOwner() != null) {
            phSelf.put("%filterOwner%", uiContext.filterOwner().toString());
        } else if (uiContext.admin()) {
            phSelf.put("%filterOwner%", "ALL");
        }
        builder.title(MiniMessageUtil.parseOrPlain(cfg.title, phSelf));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.instruction, phSelf)));
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.cancelHint, phSelf)));

        // Start capture mode now delegates fully to VaultCaptureService
        builder.button(MiniMessageUtil.parseOrPlain(cfg.startBtn, phSelf), ctx -> {
            Player actor = ctx.player();
            if (!VaultPermission.ACTION_CAPTURE.has(actor)) {
                actor.sendMessage("You don't have permission to capture blocks.");
                return;
            }
            VaultCaptureService svc = VaultStoragePlugin.getInstance().getCaptureService();
            svc.startCaptureSession(actor); // centralized session (action bar + listener)
            actor.closeDialog();
        });

        builder.button(MiniMessageUtil.parseOrPlain(cfg.openScanBtn, phSelf), ctx -> new VaultScanMenu(ctx.player(), this, uiContext).open());
        builder.button(MiniMessageUtil.parseOrPlain(cfg.browseBtn, phSelf), ctx -> new VaultListMenu(ctx.player(), this, uiContext, "").open());
        builder.button(MiniMessageUtil.parseOrPlain(cfg.closeBtn, phSelf), ctx -> {});
        return builder.build();
    }

    /** Public accessor for empty capture message (used by command subcaptures). */
    public static String emptyCaptureMessage() { return cfg().emptyCaptureSkipped; }

    @Override
    public void open() {
        setDialog(build());
        super.open();
    }
}
