package com.minecraftcitiesnetwork.vaultstorage.vault;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import com.minecraftcitiesnetwork.vaultstorage.database.ClaimResult;
import com.minecraftcitiesnetwork.vaultstorage.database.FindResult;
import com.minecraftcitiesnetwork.vaultstorage.database.StoredVault;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultItem;
import com.minecraftcitiesnetwork.vaultstorage.database.VaultItemEntity;
import com.minecraftcitiesnetwork.vaultstorage.menu.LoadingMenu;
import com.minecraftcitiesnetwork.vaultstorage.menu.ParentMenu;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a vault as an inventory, to view it, copy from it or edit it.
 *
 * <p>One listener serves every open vault; it recognises them by their {@link VaultView} holder.
 * Editing claims the vault for as long as it is open, so two people cannot edit it, or edit it
 * while it is being placed, and saves the whole contents in one transaction on close.
 */
public class VaultInventoryService implements Listener {

    private static final int MAX_SIZE = 54;
    private static final int ROW = 9;

    /** Opens a virtual inventory for the given vault id and action. */
    public void openVirtualInventory(@NotNull Player player, @NotNull UUID vaultId, @NotNull VaultAction action) {
        openVirtualInventory(player, vaultId, action, null, null);
    }

    /**
     * Opens the vault for the player. With a parent menu, a loading menu is shown meanwhile.
     * {@code reopenCallback} runs on the main thread after the inventory closes.
     */
    public void openVirtualInventory(@NotNull Player player, @NotNull UUID vaultId, @NotNull VaultAction action,
                                     @Nullable ParentMenu parentMenu, @Nullable Runnable reopenCallback) {
        VaultStoragePlugin plugin = VaultStoragePlugin.getInstance();
        Executor mainThread = plugin.getServer().getScheduler().getMainThreadExecutor(plugin);
        if (parentMenu != null) {
            Map<String, String> placeholders = Map.of("%player%", player.getName(), "%vault%", String.valueOf(vaultId));
            mainThread.execute(() -> new LoadingMenu(player, parentMenu, placeholders).open());
        }
        boolean editing = VaultViewRules.savesOnClose(action);
        CompletableFuture
                .supplyAsync(() -> load(plugin, vaultId, editing), plugin.databaseExecutor())
                .thenCompose(loaded -> {
                    if (loaded.vault().isEmpty()) {
                        return CompletableFuture.completedFuture(Map.entry(loaded, ""));
                    }
                    UUID owner = loaded.vault().get().vault().ownerUuid();
                    return plugin.names().nameOf(owner).thenApply(name ->
                            Map.entry(loaded, name.filter(found -> !found.isBlank()).orElse(owner.toString())));
                })
                .thenAcceptAsync(entry -> {
                    Loaded loaded = entry.getKey();
                    if (loaded.vault().isEmpty()) {
                        player.sendMessage(loaded.refusal());
                        return;
                    }
                    if (!player.isOnline()) {
                        releaseIfEditing(plugin, vaultId, editing);
                        return;
                    }
                    show(player, loaded.vault().get(), entry.getValue(), action, reopenCallback);
                }, mainThread)
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not open vault " + vaultId + ".", failure);
                    releaseIfEditing(plugin, vaultId, editing);
                    player.sendMessage("The vault could not be opened.");
                    return null;
                });
    }

    /** A loaded vault, or the reason there is none to show. */
    private record Loaded(@NotNull Optional<StoredVault> vault, @NotNull String refusal) {
    }

    private static @NotNull Loaded load(@NotNull VaultStoragePlugin plugin, @NotNull UUID vaultId, boolean editing) {
        if (editing) {
            return switch (plugin.repository().claim(vaultId)) {
                case ClaimResult.Claimed claimed -> new Loaded(Optional.of(claimed.vault()), "");
                case ClaimResult.AlreadyClaimed held -> new Loaded(Optional.empty(), "Someone else is using that vault.");
                case ClaimResult.NoSuchVault missing -> new Loaded(Optional.empty(), "Vault not found.");
            };
        }
        return switch (plugin.repository().find(vaultId)) {
            case FindResult.Found found -> new Loaded(Optional.of(found.vault()), "");
            case FindResult.NoSuchVault missing -> new Loaded(Optional.empty(), "Vault not found.");
        };
    }

    private static void releaseIfEditing(@NotNull VaultStoragePlugin plugin, @NotNull UUID vaultId, boolean editing) {
        if (!editing) {
            return;
        }
        CompletableFuture.runAsync(() -> plugin.repository().release(vaultId), plugin.databaseExecutor())
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Could not release vault " + vaultId + ".", failure);
                    return null;
                });
    }

    private static void show(@NotNull Player player, @NotNull StoredVault stored, @NotNull String ownerName,
                             @NotNull VaultAction action, @Nullable Runnable reopenCallback) {
        int highestSlot = stored.items().stream().mapToInt(VaultItemEntity::slot).max().orElse(-1);
        int size = Math.min(MAX_SIZE, Math.max(ROW, ((highestSlot + 1 + ROW - 1) / ROW) * ROW));
        Component title = Component.text("Owner: ", NamedTextColor.WHITE)
                .append(Component.text(ownerName, NamedTextColor.GOLD))
                .append(Component.text(" - " + action.name(), NamedTextColor.GRAY));
        VaultView view = new VaultView(stored.vault().uuid(), action, reopenCallback);
        Inventory inventory = Bukkit.createInventory(view, size, title);
        view.inventory(inventory);
        for (VaultItemEntity row : stored.items()) {
            if (row.slot() >= 0 && row.slot() < size) {
                inventory.setItem(row.slot(), ItemSerialization.fromBytes(row.item()));
            }
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof VaultView view)) {
            return;
        }
        boolean clickedVault = event.getClickedInventory() == event.getView().getTopInventory();
        ItemStack current = event.getCurrentItem();
        if (VaultViewRules.cancelsClick(view.action())) {
            event.setCancelled(true);
        }
        if (VaultViewRules.givesCopy(view.action(), clickedVault, current != null && !current.getType().isAir())
                && event.getWhoClicked() instanceof Player player) {
            for (ItemStack leftover : player.getInventory().addItem(current.clone()).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    @EventHandler
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof VaultView view)) {
            return;
        }
        int vaultSlots = event.getView().getTopInventory().getSize();
        boolean touchesVault = event.getRawSlots().stream().anyMatch(slot -> slot < vaultSlots);
        if (VaultViewRules.cancelsDrag(view.action(), touchesVault)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof VaultView view)) {
            return;
        }
        VaultStoragePlugin plugin = VaultStoragePlugin.getInstance();
        Runnable afterClose = view.afterClose();
        Executor mainThread = plugin.getServer().getScheduler().getMainThreadExecutor(plugin);
        if (!VaultViewRules.savesOnClose(view.action())) {
            if (afterClose != null) {
                mainThread.execute(afterClose);
            }
            return;
        }
        List<VaultItem> items = itemsOf(event.getInventory());
        CompletableFuture.runAsync(() -> saveAndRelease(plugin, view.vault(), items), plugin.databaseExecutor())
                .exceptionally(failure -> {
                    plugin.getLogger().log(Level.SEVERE, "Vault " + view.vault() + " could not be saved after "
                            + event.getPlayer().getName() + " edited it. It keeps its previous contents; what they"
                            + " put in is lost and what they took out exists twice.", failure);
                    event.getPlayer().sendMessage("The vault could not be saved. Tell staff.");
                    return null;
                })
                .thenRunAsync(() -> {
                    if (afterClose != null) {
                        afterClose.run();
                    }
                }, mainThread);
    }

    private static @NotNull List<VaultItem> itemsOf(@NotNull Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        List<VaultItem> items = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack != null && !stack.getType().isAir() && stack.getAmount() > 0) {
                items.add(new VaultItem(slot, stack.getAmount(), ItemSerialization.toBytes(stack)));
            }
        }
        return items;
    }

    /** Saves, then gives the vault back even if the save failed, so it is not held forever. */
    private static void saveAndRelease(@NotNull VaultStoragePlugin plugin, @NotNull UUID vault, @NotNull List<VaultItem> items) {
        try {
            plugin.repository().replaceItems(vault, items);
        } finally {
            plugin.repository().release(vault);
        }
    }

    /**
     * Saves every vault open for editing, on the calling thread. For shutdown only: the database
     * pool is about to close, so the save cannot be handed to it.
     */
    public void saveOpenViews() {
        VaultStoragePlugin plugin = VaultStoragePlugin.getInstance();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory open = player.getOpenInventory().getTopInventory();
            if (!(open.getHolder() instanceof VaultView view) || !VaultViewRules.savesOnClose(view.action())) {
                continue;
            }
            try {
                saveAndRelease(plugin, view.vault(), itemsOf(open));
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.SEVERE, "Vault " + view.vault() + " could not be saved at shutdown.", ex);
            }
        }
    }
}
