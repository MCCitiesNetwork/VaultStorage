package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Child dialog that lists vaults from the database using VaultService.
 * Loads results asynchronously to avoid blocking the main thread.
 */
public class VaultListMenu extends ChildMenuImp {

    private final String query;
    private final List<Entry> entries;

    private record Entry(UUID id, String ownerName, int indexWithinOwner) {}

    /** Configuration DTO for menu texts. */
    public static class Config implements Dto, Serializable {
        /** Dialog title. Supports %query% placeholder. */
        public String title = "<gold><bold>Browse Vaults</bold></gold>";
        /** Label above the search text box. Supports %query% placeholder. */
        public String searchLabel = "<gray>Search by owner name or UUID</gray>";
        /** Button to refresh with the typed query. Supports %query%. */
        public String searchBtn = "<aqua>Search</aqua>";
        /** Button to go back to parent menu. */
        public String backBtn = "<red><bold>Back</bold></red>";
        /** Message when no results match the query. Supports %query%. */
        public String noneFound = "<gray>No vaults found.</gray>";
        /** Loading message while fetching results. */
        public String loading = "<gray>Loading...</gray>";
        /**
         * Format for each listed vault button.
         * Placeholders:
         * - %owner%: resolved owner name or UUID
         * - %index%: 1-based index among this owner's vaults within the result set
         */
        public String itemFormat = "<white>%owner%</white><gray>'s Vault #</gray><white>%index%</white>";
    }

    private static final String HEADER = String.join("\n",
            "VaultListMenu configuration.",
            "Fields accept MiniMessage or plain strings.",
            "Placeholders for global context:",
            "- %query% -> current search query",
            "Placeholders for itemFormat:",
            "- %owner% -> owner name or UUID",
            "- %index% -> 1-based index among the owner's vaults"
    );

    /** AutoYML handler to persist this menu's configuration. */
    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultListMenu", DataFolder.MENUS, HEADER);

    /** Ensures the YAML file for this menu exists by creating defaults if missing. */
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }

    /** Lazily loaded configuration snapshot. */
    private static Config cfg() { return YML.loadOrCreate(Config::new); }

    /** Creates a menu and triggers async loading with the given query. */
    public VaultListMenu(Player player, ParentMenuImp parent, String query) {
        super(player, parent, "vault_list");
        this.query = query == null ? "" : query.trim();
        this.entries = null; // loading
        setDialog(build());
        loadAsync();
    }

    /** Internal constructor with precomputed entries. */
    private VaultListMenu(Player player, ParentMenuImp parent, String query, List<Entry> entries) {
        super(player, parent, "vault_list");
        this.query = query == null ? "" : query.trim();
        this.entries = entries == null ? List.of() : List.copyOf(entries);
        setDialog(build());
    }

    @Override
    public Dialog getDialog() { return super.getDialog(); }

    private Dialog build() {
        Config cfg = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> phQuery = Map.of("%query%", query == null ? "" : query);
        builder.title(MiniMessageUtil.parseOrPlain(cfg.title, phQuery));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addInput(DialogInput.text("QUERY", MiniMessageUtil.parseOrPlain(cfg.searchLabel, phQuery)).labelVisible(true).build());
        builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(cfg.searchBtn, phQuery), null, java.time.Duration.ofMinutes(5), 1, (player, response) -> {
            String q = Optional.ofNullable(response.getText("QUERY")).orElse("").trim();
            new VaultListMenu(player, (ParentMenuImp) getParentMenu(), q).open();
        });

        if (entries == null) {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.loading)));
        } else if (entries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.noneFound, phQuery)));
        } else {
            for (Entry e : entries) {
                Map<String, String> ph = Map.of("%owner%", e.ownerName(), "%index%", String.valueOf(e.indexWithinOwner()));
                Component label = MiniMessageUtil.parseOrPlain(cfg.itemFormat, ph);
                builder.button(label, ctx -> new VaultActionMenu(ctx.player(), (ParentMenuImp) getParentMenu(), e.id()).open());
            }
        }

        builder.button(MiniMessageUtil.parseOrPlain(cfg.backBtn), ctx -> getParentMenu().open());
        return builder.build();
    }

    private void loadAsync() {
        Player p = getPlayer();
        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable() {
            @Override public void run() {
                VaultService vs = plugin.getVaultService();
                List<net.democracycraft.vault.internal.database.entity.VaultEntity> vaults;
                String q = VaultListMenu.this.query;
                if (q == null || q.isBlank()) {
                    vaults = vs.listInWorld(p.getWorld().getUID());
                } else {
                    UUID owner = parseUuidOrName(q);
                    if (owner != null) vaults = vs.listByOwner(owner); else vaults = List.of();
                }
                // Resolve owner names and index per owner
                Map<UUID,Integer> ownerCounts = new ConcurrentHashMap<>();
                List<Entry> out = new ArrayList<>();
                for (var v : vaults) {
                    UUID ownerUuid = vs.getOwner(v.uuid);
                    String name = ownerUuid == null ? "Unknown" : Optional.ofNullable(Bukkit.getOfflinePlayer(ownerUuid).getName()).orElse(ownerUuid.toString());
                    int idx = ownerCounts.merge(ownerUuid == null ? new UUID(0,0) : ownerUuid, 1, Integer::sum);
                    out.add(new Entry(v.uuid, name, idx));
                }
                new BukkitRunnable() {
                    @Override public void run() {
                        new VaultListMenu(p, (ParentMenuImp) getParentMenu(), query, out).open();
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private static UUID parseUuidOrName(String q) {
        try { return UUID.fromString(q); } catch (IllegalArgumentException ignore) {}
        OfflinePlayer op = Bukkit.getOfflinePlayer(q);
        return op != null && op.getUniqueId() != null ? op.getUniqueId() : null;
    }

    @Override
    public void open() {
        // Rebuild UI to reflect latest YAML configuration each time the menu opens
        setDialog(build());
        super.open();
    }
}
