package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.config.Text;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import com.minecraftcitiesnetwork.vaultstorage.menu.AutoDialog;
import com.minecraftcitiesnetwork.vaultstorage.menu.ParentMenu;
import org.bukkit.entity.Player;

import java.util.Map;

/**
 * Lightweight, multi-purpose loading child menu to indicate progress while work happens asynchronously.
 * This menu is YAML-configurable and supports placeholders like other menus.
 */
public class LoadingMenu extends ChildMenuImp {




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
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(Text.of(MessageKeys.LOADING_TITLE, placeholders));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);
        builder.addBody(DialogBody.plainMessage(Text.of(MessageKeys.LOADING_MESSAGE, placeholders)));
        return builder.build();
    }

    @Override
    public void open() {
        if(VaultStoragePlugin.getInstance().integrations().isBedrockClient(player.getUniqueId())){
            player.sendMessage(Text.of(MessageKeys.LOADING_BEDROCK));
        }else{
            setDialog(build());
            super.open();
        }
    }
}
