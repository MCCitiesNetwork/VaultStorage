package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.api.ui.ChildMenu;
import net.democracycraft.vault.api.ui.ParentMenu;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Base implementation of a ParentMenu used to build Paper Dialog UIs.
 * Provides helper methods for common UI tasks (MiniMessage parsing).
 */
public abstract class ParentMenuImp implements ParentMenu {
    private final Player player;
    private final String id;
    private Dialog dialog;
    private final List<ChildMenu> children = new ArrayList<>();

    protected ParentMenuImp(Player player, String id) {
        this.player = player;
        this.id = id;
    }

    @Override
    public String getId() { return id; }

    @Override
    public Dialog getDialog() { return dialog; }

    @Override
    public void open() {
        if (dialog != null) player.showDialog(dialog);
    }

    @Override
    public Player getPlayer() { return player; }

    @Override
    public void setDialog(Dialog dialog) { this.dialog = dialog; }

    @Override
    public AutoDialog.Builder getAutoDialogBuilder() { return AutoDialog.builder(this); }

    @Override
    public List<ChildMenu> getChildMenus() { return children; }

    @Override
    public void addChildMenu(ChildMenu childMenu) { children.add(childMenu); }

    @Override
    public void addChildMenus(List<ChildMenu> childMenus) { children.addAll(childMenus); }

    /**
     * Parse MiniMessage-formatted text into a Component, falling back to plain text.
     */
    protected Component miniMessage(String mm) {
        return MiniMessageUtil.parseOrPlain(mm == null ? "" : mm);
    }
}
