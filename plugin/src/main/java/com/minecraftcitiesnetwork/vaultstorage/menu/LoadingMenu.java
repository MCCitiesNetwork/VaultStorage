package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import java.util.Map;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shown while something loads. It has no buttons: whoever opens it replaces it with the next
 * menu, or closes it before saying what went wrong.
 */
public final class LoadingMenu extends ChildMenuImp {

    private final @NotNull Map<String, String> placeholders;

    /** @param placeholders for an operator's wording, such as %player% */
    public LoadingMenu(@NotNull Player player, @NotNull ParentMenu parentMenu, @Nullable Map<String, String> placeholders) {
        super(player, parentMenu);
        this.placeholders = placeholders == null ? Map.of() : placeholders;
    }

    @Override
    protected @NotNull AutoDialog build() {
        return AutoDialog.builder()
                .title(plugin.text().of(MessageKeys.LOADING_TITLE, placeholders))
                .line(plugin.text().of(MessageKeys.LOADING_MESSAGE, placeholders))
                .build();
    }
}
