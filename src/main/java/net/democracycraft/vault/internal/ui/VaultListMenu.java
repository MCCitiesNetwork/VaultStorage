package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.convertible.Vault;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.ui.AutoDialog;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Child dialog that lists captured vaults with their owner's name resolved via Bukkit#getOfflinePlayer(UUID).
 * Includes a text query to filter by owner name or UUID, and a search button that refreshes the dialog.
 */
public class VaultListMenu extends ChildMenuImp {

    private final String query;

    /** Configuration DTO for menu texts. */
    public static class Config implements Dto {
        public String title = "<gold><bold>Browse Vaults</bold></gold>";
        public String searchLabel = "<gray>Search by owner name or UUID</gray>";
        public String searchBtn = "<aqua>Search</aqua>";
        public String backBtn = "<red><bold>Back</bold></red>";
        public String noneFound = "<gray>No vaults found.</gray>";
        public String itemFormat = "<white>%owner%</white><gray>'s Vault #</gray><white>%index%</white>";
    }

    /**
     * @param player viewer
     * @param parent parent menu
     * @param query initial query (nullable)
     */
    public VaultListMenu(Player player, ParentMenuImp parent, String query) {
        super(player, parent, "vault_list");
        this.query = query == null ? "" : query.trim();
        setDialog(build());
    }

    @Override
    public Dialog getDialog() { return super.getDialog(); }

    private Dialog build() {
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(miniMessage(new Config().title));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        // Search input (no initial value API available in DialogInput.text)
        builder.addInput(DialogInput.text("QUERY", miniMessage(new Config().searchLabel)).labelVisible(true).build());
        builder.buttonWithPlayer(miniMessage(new Config().searchBtn), null, java.time.Duration.ofMinutes(5), 1, (player, response) -> {
            String q = Optional.ofNullable(response.getText("QUERY")).orElse("").trim();
            new VaultListMenu(player, (ParentMenuImp) getParentMenu(), q).open();
        });

        // Data listing via SessionManager
        var sessionMgr = VaultStoragePlugin.getInstance().getSessionManager();
        List<Vault> all = sessionMgr.getAllVaults();
        List<Vault> filtered = filter(all, query);
        if (filtered.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(miniMessage(new Config().noneFound)));
        } else {
            for (Vault v : filtered) {
                String ownerName = resolveOwnerName(v.ownerUniqueIdentifier());
                int index = sessionMgr.indexWithinOwner(v.ownerUniqueIdentifier(), v.uniqueIdentifier());
                Map<String, String> ph = Map.of("%owner%", ownerName, "%index%", String.valueOf(index < 0 ? 1 : index));
                builder.button(miniMessage(apply(new Config().itemFormat, ph)), ctx -> new VaultActionMenu(ctx.player(), (ParentMenuImp) getParentMenu(), v.uniqueIdentifier()).open());
            }
        }

        builder.button(miniMessage(new Config().backBtn), ctx -> getParentMenu().open());
        return builder.build();
    }

    private static String resolveOwnerName(UUID owner) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
        String name = op.getName();
        return name == null ? owner.toString() : name;
    }

    private static List<Vault> filter(List<Vault> list, String q) {
        if (q == null || q.isBlank()) return list;
        String s = q.toLowerCase(Locale.ROOT);
        return list.stream().filter(v -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(v.ownerUniqueIdentifier());
            String name = op.getName();
            String owner = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return owner.contains(s) || v.ownerUniqueIdentifier().toString().toLowerCase(Locale.ROOT).contains(s) || v.uniqueIdentifier().toString().toLowerCase(Locale.ROOT).contains(s);
        }).collect(Collectors.toList());
    }

    private static String apply(String text, Map<String, String> ph) {
        String s = text == null ? "" : text;
        if (ph != null) {
            for (var e : ph.entrySet()) s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }
}
