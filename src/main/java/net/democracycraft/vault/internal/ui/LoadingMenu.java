package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.api.ui.ParentMenu;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Lightweight, multi-purpose loading child menu to indicate progress while work happens asynchronously.
 * This menu is YAML-configurable and supports placeholders like other menus.
 */
public class LoadingMenu extends ChildMenuImp {

    /** YAML-backed configuration for the loading UI. */
    public static class Config implements Dto {
        /** Dialog title. Placeholders: %player% %vault% */
        public String title = "<yellow><bold>Loading</bold></yellow>";
        /** Body message. Placeholders: %player% %vault% */
        public String message = "<gray>Please wait...</gray>";
    }

    private static final String HEADER = String.join("\n",
            "LoadingMenu configuration.",
            "Placeholders:",
            "- %player% -> actor/player name",
            "- %vault% -> vault UUID when available"
    );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "LoadingMenu", DataFolder.MENUS, HEADER);
    private static Config cfg() { return YML.loadOrCreate(Config::new); }
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }

    private final Map<String,String> placeholders;

    /**
     * Creates a new configurable loading child menu.
     *
     * @param player      the target player
     * @param parentMenu  the parent menu to which this child belongs
     * @param placeholders placeholder map (e.g., %player%, %vault%)
     */
    public LoadingMenu(Player player, ParentMenu parentMenu, Map<String,String> placeholders) {
        super(player, parentMenu, "loading_menu");
        this.placeholders = placeholders == null ? Map.of() : placeholders;
        setDialog(build());
    }

    private Dialog build() {
        Config c = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(MiniMessageUtil.parseOrPlain(c.title, placeholders));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(c.message, placeholders)));
        return builder.build();
    }

    @Override
    public void open() {
        setDialog(build());
        super.open();
    }
}
