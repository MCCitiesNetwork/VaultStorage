package com.minecraftcitiesnetwork.vaultstorage.menu;

import com.minecraftcitiesnetwork.pluginInfrastructure.configurate.MessageContainer;
import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.config.MessageKeys;
import com.minecraftcitiesnetwork.vaultstorage.database.FindResult;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultFilter;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultPage;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultRepository;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultSummary;
import com.minecraftcitiesnetwork.vaultstorage.names.Names;
import com.minecraftcitiesnetwork.vaultstorage.names.UniqueIdentifierResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Lists vaults, a page at a time. An owner sees their own vaults; an admin sees everyone's and
 * can search by owner name, owner UUID or vault UUID.
 *
 * <p>The page is read on the database executor, one count and one query however many vaults
 * exist; a loading menu is shown meanwhile.
 */
public final class VaultListMenu extends ChildMenuImp {

    /** Wide enough for a whole label; the client shrinks it to the window. */
    private static final int LABEL_WIDTH = 400;
    private static final String KEY_QUERY = "QUERY";
    private static final int QUERY_LENGTH = 255;

    private final @NotNull VaultUIContext uiContext;
    private final @NotNull String query;
    private final int requestedPage;
    /** Null until the page has been loaded. */
    private final @Nullable Loaded loaded;

    private record Entry(@NotNull VaultSummary vault, @NotNull String ownerName, int index) {
    }

    private record Loaded(@NotNull List<Entry> entries, @NotNull Paging paging) {
    }

    /** Opens the first page. */
    public VaultListMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx,
                         @NotNull String query) {
        this(player, parent, ctx, query, 0);
    }

    /** Opens the given page, zero-based; a page past the end shows the last one. */
    public VaultListMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx,
                         @NotNull String query, int page) {
        this(player, parent, ctx, query, page, null);
    }

    private VaultListMenu(@NotNull Player player, @NotNull ParentMenu parent, @NotNull VaultUIContext ctx,
                          @NotNull String query, int page, @Nullable Loaded loaded) {
        super(player, parent);
        this.uiContext = ctx;
        this.query = query.trim();
        this.requestedPage = page;
        this.loaded = loaded;
    }

    /** Shows the loading menu and reads the page, or shows the page that was read. */
    @Override
    public void open() {
        if (this.loaded != null) {
            super.open();
            return;
        }
        new LoadingMenu(player, parentMenu(), Map.of("%query%", this.query)).open();
        load();
    }

    /** Vaults per page, from settings.yml; read each time, so a reload takes effect at the next open. */
    private int pageSize() {
        return plugin.settings().vaultList().pageSize();
    }

    private @NotNull VaultListMenu at(@NotNull Player actor, @NotNull String search, int page) {
        return new VaultListMenu(actor, parentMenu(), this.uiContext, search, page);
    }

    @Override
    protected @NotNull AutoDialog build() {
        Loaded page = Objects.requireNonNull(this.loaded, "the list is built once its page is loaded");
        Map<String, String> phQuery = Map.of("%query%", this.query);
        AutoDialog.Builder builder = AutoDialog.builder().title(plugin.text().of(MessageKeys.LIST_TITLE, phQuery));

        // Only someone who may see everyone's vaults has anything to search for.
        if (this.uiContext.filterOwner() == null) {
            builder.textInput(KEY_QUERY, plugin.text().of(MessageKeys.LIST_SEARCH_LABEL, phQuery), QUERY_LENGTH);
            builder.submitButton(plugin.text().of(MessageKeys.LIST_SEARCH_BTN, phQuery),
                    (actor, answers) -> at(actor, answers.getOrDefault(KEY_QUERY, ""), 0).open());
        }

        Paging paging = page.paging();
        if (page.entries().isEmpty()) {
            builder.line(plugin.text().of(MessageKeys.LIST_NONE_FOUND, phQuery));
        } else {
            builder.line(plugin.text().of(MessageKeys.LIST_PAGE_LABEL, Map.of(
                    "%page%", String.valueOf(paging.page() + 1),
                    "%pages%", String.valueOf(paging.pages()),
                    "%count%", String.valueOf(paging.total()))));
            for (Entry entry : page.entries()) {
                Component label = label(entry);
                // Back from the vault returns to this page of this search.
                builder.wideButton(label, LABEL_WIDTH, actor -> new VaultActionMenu(actor, parentMenu(),
                        this.uiContext, entry.vault().uuid(), label,
                        returning -> at(returning, this.query, paging.page()).open()).open());
            }
        }
        if (paging.hasPrevious()) {
            builder.button(plugin.text().of(MessageKeys.LIST_PREV_BTN),
                    actor -> at(actor, this.query, paging.page() - 1).open());
        }
        if (paging.hasNext()) {
            builder.button(plugin.text().of(MessageKeys.LIST_NEXT_BTN),
                    actor -> at(actor, this.query, paging.page() + 1).open());
        }
        builder.button(plugin.text().of(MessageKeys.LIST_BACK_BTN), actor -> parentMenu().open());
        return builder.build();
    }

    /** Who owns the vault, what it was, where it stood, how much it holds and when it was captured. */
    private @NotNull Component label(@NotNull Entry entry) {
        VaultSummary vault = entry.vault();
        MessageContainer messages = plugin.messages();
        World world = Bukkit.getWorld(vault.worldUuid());
        Component items = messages.messageFor(VaultLabel.itemsKey(vault.itemCount()),
                MessageContainer.value("count", String.valueOf(vault.itemCount())));
        return messages.messageFor(MessageKeys.LIST_ITEM_FORMAT,
                MessageContainer.value("owner", entry.ownerName()),
                MessageContainer.value("index", String.valueOf(entry.index())),
                MessageContainer.value("material", VaultLabel.materialName(vault.material())),
                MessageContainer.value("world", world != null ? world.getName() : Names.shortId(vault.worldUuid())),
                MessageContainer.value("x", String.valueOf(vault.x())),
                MessageContainer.value("y", String.valueOf(vault.y())),
                MessageContainer.value("z", String.valueOf(vault.z())),
                MessageContainer.markup("items", items),
                MessageContainer.value("date", VaultLabel.date(vault.createdTime())));
    }

    /** What the list covers: one owner's vaults, one vault, or everything. */
    private record Scope(@Nullable UUID owner, @Nullable UUID vault, boolean nothing) {
        static @NotNull Scope everything() { return new Scope(null, null, false); }
        static @NotNull Scope ownedBy(@NotNull UUID owner) { return new Scope(owner, null, false); }
        static @NotNull Scope oneVault(@NotNull UUID vault) { return new Scope(null, vault, false); }
        static @NotNull Scope empty() { return new Scope(null, null, true); }
    }

    /** Works out the scope from the context and what was typed. May look a name up. */
    private @NotNull CompletableFuture<Scope> scope(@NotNull VaultStoragePlugin plugin) {
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

    /** Database thread. One count and one query: a page past the end becomes the last page. */
    private @NotNull VaultPage read(@NotNull VaultRepository repository, @NotNull Scope scope) {
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
        return repository.pageAt(new VaultFilter(scope.owner(), null), this.requestedPage, pageSize());
    }

    private void load() {
        Executor mainThread = plugin.mainThread();
        scope(plugin)
                .thenApplyAsync(scope -> read(plugin.repository(), scope), plugin.databaseExecutor())
                .thenCompose(page -> plugin.names()
                        .displayNames(page.vaults().stream().map(VaultSummary::ownerUuid).toList())
                        .thenApply(names -> {
                            Paging paging = Paging.of(page.total(), pageSize(), this.requestedPage);
                            List<Entry> entries = new ArrayList<>();
                            int index = paging.offset() + 1;
                            for (VaultSummary vault : page.vaults()) {
                                entries.add(new Entry(vault, names.getOrDefault(vault.ownerUuid(), Names.shortId(vault.ownerUuid())), index++));
                            }
                            return new Loaded(entries, paging);
                        }))
                .thenAcceptAsync(result -> {
                    if (player.isOnline()) {
                        new VaultListMenu(player, parentMenu(), this.uiContext, this.query, this.requestedPage,
                                result).open();
                    }
                }, mainThread)
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not load the vault list for " + player.getName() + ".", failure);
                    mainThread.execute(() -> {
                        if (player.isOnline()) {
                            player.sendMessage(plugin.text().of(MessageKeys.LIST_LOAD_FAILED));
                            parentMenu().open();
                        }
                    });
                    return null;
                });
    }
}
