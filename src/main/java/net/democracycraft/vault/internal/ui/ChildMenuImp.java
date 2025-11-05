package net.democracycraft.vault.internal.ui;

import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.api.ui.ChildMenu;
import net.democracycraft.vault.api.ui.ParentMenu;
import org.bukkit.entity.Player;

/**
 * Base implementation for child menus that participate in a parent menu flow.
 * Provides a reference to the parent menu and delegates dialog builder to it.
 */
public abstract class ChildMenuImp extends ParentMenuImp implements ChildMenu {

    private final ParentMenu parent;

    protected ChildMenuImp(Player player, ParentMenu parent, String id) {
        super(player, id);
        this.parent = parent;
    }

    @Override
    public ParentMenu getParentMenu() {
        return parent;
    }

    @Override
    public AutoDialog.Builder getAutoDialogBuilder() {
        return AutoDialog.builder(parent);
    }
}
