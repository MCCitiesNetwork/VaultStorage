package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.AutoYML;
import com.minecraftcitiesnetwork.vaultstorage.config.DataFolder;
import com.minecraftcitiesnetwork.vaultstorage.config.MiniMessageUtil;
import com.minecraftcitiesnetwork.vaultstorage.database.FindResult;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultFilter;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultPage;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultRepository;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultSummary;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import com.minecraftcitiesnetwork.vaultstorage.vault.Dto;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists vaults, a page at a time. An owner sees their own vaults; an admin sees everyone's and
 * can search by owner name, owner UUID or vault UUID.
 *
 * <p>The page is read on the database executor, one count and one query however many vaults
 * exist. It used to read every vault on the main thread and show each as a button.
 */
public class VaultListMenu extends ChildMenuImp {

    /** Vaults per page: enough to be useful, few enough for a dialog. */
    static final int PAGE_SIZE = 20;

    private final VaultUIContext uiContext;
    private final String query;
    private final int requestedPage;
    /** Null until the page has been loaded. */
    private final @Nullable Loaded loaded;

    private record Entry(UUID id, String ownerName, int index) {}

    private record Loaded(List<Entry> entries, Paging paging) {}

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
        /**
         * Format for each listed vault button.
         * Placeholders:
         * - %owner%: resolved owner name or UUID
         * - %index%: 1-based index among this owner's vaults within the result set
         */
        public String itemFormat = "<white>%owner%</white><gray>'s Vault #</gray><white>%index%</white>";
        /** Button to the previous page. */
        public String prevBtn = "<yellow>< Prev</yellow>";
        /** Button to the next page. */
        public String nextBtn = "<yellow>Next ></yellow>";
        /** Line showing where the page is in the list. Placeholders: %page% %pages% %count% */
        public String pageLabel = "<gray>Page %page% / %pages% (%count% vaults)</gray>";
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

    /** Opens the first page. */
    public VaultListMenu(Player player, ParentMenuImp parent, VaultUIContext ctx, String query) {
        this(player, parent, ctx, query, 0);
    }

    /** Opens the given page, zero-based; a page past the end shows the last one. */
    public VaultListMenu(Player player, ParentMenuImp parent, VaultUIContext ctx, String query, int page) {
        this(player, parent, ctx, query, page, null);
    }

    private VaultListMenu(Player player, ParentMenuImp parent, VaultUIContext ctx, String query, int page,
                          @Nullable Loaded loaded) {
        super(player, parent, "vault_list");
        this.uiContext = ctx;
        this.query = query == null ? "" : query.trim();
        this.requestedPage = page;
        this.loaded = loaded;
        if (loaded != null) {
            setDialog(build(loaded));
        }
    }

    @Override
    public Dialog getDialog() { return super.getDialog(); }

    private Dialog build(@NotNull Loaded page) {
        Config cfg = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String, String> phQuery = Map.of("%query%", this.query);
        builder.title(MiniMessageUtil.parseOrPlain(cfg.title, phQuery));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);
        ParentMenuImp parent = (ParentMenuImp) getParentMenu();

        // Only someone who may see everyone's vaults has anything to search for.
        if (this.uiContext.filterOwner() == null) {
            builder.addInput(DialogInput.text("QUERY", MiniMessageUtil.parseOrPlain(cfg.searchLabel, phQuery))
                    .maxLength(255)
                    .labelVisible(true)
                    .build());
            builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(cfg.searchBtn, phQuery), null, Duration.ofMinutes(5), 1,
                    (player, response) -> {
                        String typed = Optional.ofNullable(response.getText("QUERY")).orElse("").trim();
                        new VaultListMenu(player, parent, this.uiContext, typed).open();
                    });
        }

        Paging paging = page.paging();
        if (page.entries().isEmpty()) {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.noneFound, phQuery)));
        } else {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.pageLabel, Map.of(
                    "%page%", String.valueOf(paging.page() + 1),
                    "%pages%", String.valueOf(paging.pages()),
                    "%count%", String.valueOf(paging.total())))));
            for (Entry entry : page.entries()) {
                Component label = MiniMessageUtil.parseOrPlain(cfg.itemFormat,
                        Map.of("%owner%", entry.ownerName(), "%index%", String.valueOf(entry.index())));
                builder.button(label, ctx -> new VaultActionMenu(ctx.player(), parent, this.uiContext, entry.id()).open());
            }
        }
        if (paging.hasPrevious()) {
            builder.button(MiniMessageUtil.parseOrPlain(cfg.prevBtn),
                    ctx -> new VaultListMenu(ctx.player(), parent, this.uiContext, this.query, paging.page() - 1).open());
        }
        if (paging.hasNext()) {
            builder.button(MiniMessageUtil.parseOrPlain(cfg.nextBtn),
                    ctx -> new VaultListMenu(ctx.player(), parent, this.uiContext, this.query, paging.page() + 1).open());
        }
        builder.button(MiniMessageUtil.parseOrPlain(cfg.backBtn), ctx -> getParentMenu().open());
        return builder.build();
    }

    /** What the list covers: one owner's vaults, one vault, or everything. */
    private record Scope(@Nullable UUID owner, @Nullable UUID vault, boolean nothing) {
        static Scope everything() { return new Scope(null, null, false); }
        static Scope ownedBy(UUID owner) { return new Scope(owner, null, false); }
        static Scope oneVault(UUID vault) { return new Scope(null, vault, false); }
        static Scope empty() { return new Scope(null, null, true); }
    }

    /** Works out the scope from the context and what was typed. May look a name up. */
    private CompletableFuture<Scope> scope(VaultStoragePlugin plugin) {
        if (this.uiContext.filterOwner() != null) {
            return CompletableFuture.completedFuture(Scope.ownedBy(this.uiContext.filterOwner()));
        }
        if (this.query.isBlank()) {
            return CompletableFuture.completedFuture(Scope.everything());
        }
        try {
            UUID typed = UUID.fromString(this.query);
            // A UUID is an owner's if they have vaults, and otherwise a vault's own id.
            return CompletableFuture.supplyAsync(() ->
                    plugin.repository().count(new VaultFilter(typed, null)) > 0 ? Scope.ownedBy(typed) : Scope.oneVault(typed),
                    plugin.databaseExecutor());
        } catch (IllegalArgumentException notAUuid) {
            return new UniqueIdentifierResolver(plugin.names()).resolveUuid(this.query)
                    .thenApply(resolved -> UniqueIdentifierResolver.isValidUUID(resolved)
                            ? Scope.ownedBy(resolved) : Scope.empty());
        }
    }

    private VaultPage read(VaultRepository repository, Scope scope) {
        if (scope.nothing()) {
            return new VaultPage(List.of(), 0);
        }
        if (scope.vault() != null) {
            if (repository.find(scope.vault()) instanceof FindResult.Found found) {
                var vault = found.vault().vault();
                return new VaultPage(List.of(new VaultSummary(vault.uuid(), vault.ownerUuid(), vault.worldUuid(),
                        vault.x(), vault.y(), vault.z(), vault.material(), vault.createdTime(),
                        found.vault().items().size())), 1);
            }
            return new VaultPage(List.of(), 0);
        }
        VaultFilter filter = new VaultFilter(scope.owner(), null);
        // Count first, so a page past the end becomes the last page instead of an empty one.
        Paging paging = Paging.of(repository.count(filter), PAGE_SIZE, this.requestedPage);
        return repository.page(filter, paging.offset(), PAGE_SIZE);
    }

    private void load() {
        Player player = getPlayer();
        VaultStoragePlugin plugin = VaultStoragePlugin.getInstance();
        Executor mainThread = plugin.getServer().getScheduler().getMainThreadExecutor(plugin);
        scope(plugin)
                .thenApplyAsync(scope -> read(plugin.repository(), scope), plugin.databaseExecutor())
                .thenCompose(page -> {
                    Set<UUID> owners = new LinkedHashSet<>();
                    page.vaults().forEach(vault -> owners.add(vault.ownerUuid()));
                    Map<UUID, String> names = new HashMap<>();
                    List<CompletableFuture<Void>> lookups = new ArrayList<>();
                    for (UUID owner : owners) {
                        lookups.add(plugin.names().nameOf(owner).thenAccept(name -> {
                            synchronized (names) {
                                names.put(owner, name.filter(found -> !found.isBlank())
                                        .orElse(owner.toString().substring(0, 8)));
                            }
                        }));
                    }
                    return CompletableFuture.allOf(lookups.toArray(new CompletableFuture[0])).thenApply(done -> {
                        Paging paging = Paging.of(page.total(), PAGE_SIZE, this.requestedPage);
                        List<Entry> entries = new ArrayList<>();
                        int index = paging.offset() + 1;
                        for (VaultSummary vault : page.vaults()) {
                            entries.add(new Entry(vault.uuid(), names.get(vault.ownerUuid()), index++));
                        }
                        return new Loaded(entries, paging);
                    });
                })
                .thenAcceptAsync(result -> {
                    if (player.isOnline()) {
                        new VaultListMenu(player, (ParentMenuImp) getParentMenu(), this.uiContext, this.query,
                                this.requestedPage, result).open();
                    }
                }, mainThread)
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not load the vault list for " + player.getName() + ".", failure);
                    mainThread.execute(() -> {
                        player.sendMessage("The vault list could not be loaded.");
                        getParentMenu().open();
                    });
                    return null;
                });
    }

    @Override
    public void open() {
        if (this.loaded == null) {
            new LoadingMenu(getPlayer(), getParentMenu(), Map.of("%query%", this.query)).open();
            load();
        } else {
            setDialog(build(this.loaded));
            super.open();
        }
    }
}
