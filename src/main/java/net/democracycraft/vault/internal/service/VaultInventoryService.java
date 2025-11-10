package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.MojangService;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.api.ui.ParentMenu;
import net.democracycraft.vault.internal.ui.LoadingMenu;
import net.democracycraft.vault.internal.ui.VaultAction;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import net.democracycraft.vault.internal.util.listener.DynamicListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;

/**
 * Service to open a virtual inventory for a Vault with a specific action mode.
 * Uses VaultService to load/persist items asynchronously.
 */
public class VaultInventoryService {

    /** Opens a virtual inventory for the given vault id and action. */
    public void openVirtualInventory(Player player, UUID vaultId, VaultAction action) {
        openVirtualInventory(player, vaultId, action, null, null);
    }

    /** Opens a virtual inventory with a callback to run (on main thread) after it closes. */
    public void openVirtualInventory(Player player, UUID vaultId, VaultAction action, Runnable reopenCallback) {
        openVirtualInventory(player, vaultId, action, (ParentMenu) null, reopenCallback);
    }

    /**
     * Opens a virtual inventory for the given vault id and action.
     * Performs asynchronous owner username resolution via MojangService to avoid blocking the main thread.
     * If a parent menu is provided, a LoadingMenu child will be shown while resolving.
     */
    public void openVirtualInventory(Player player, UUID vaultId, VaultAction action, ParentMenu parentMenu, Runnable reopenCallback) {
        var plugin = VaultStoragePlugin.getInstance();
        if (parentMenu != null) {
            java.util.Map<String,String> ph = java.util.Map.of(
                    "%player%", player.getName(),
                    "%vault%", String.valueOf(vaultId)
            );
            Bukkit.getScheduler().runTask(plugin, () -> new LoadingMenu(player, parentMenu, ph).open());
        }
        new BukkitRunnable() {
            @Override public void run() {
                VaultService vs = plugin.getVaultService();
                MojangService ms = plugin.getMojangService();
                var entityOpt = vs.get(vaultId);
                if (entityOpt.isEmpty()) {
                    new BukkitRunnable() { @Override public void run() { player.sendMessage("Vault not found."); } }.runTask(plugin);
                    return;
                }
                UUID ownerUuid = vs.getOwner(vaultId);
                String ownerName = null;
                if (ownerUuid != null && ms != null) {
                    ownerName = ms.getUsername(ownerUuid); // network + cached (async thread)
                }
                String ownerDisplay = ownerName != null ? ownerName : (ownerUuid != null ? ownerUuid.toString() : "Unknown");
                var items = vs.listItems(vaultId);
                int maxSlot = items.stream().mapToInt(it -> it.slot).max().orElse(-1);
                int invSize = Math.min(54, Math.max(9, ((Math.max(maxSlot + 1, 9) + 8) / 9) * 9));
                ItemStack[] contents = new ItemStack[invSize];
                Arrays.fill(contents, null);
                for (var it : items) {
                    if (it.slot >= 0 && it.slot < invSize) contents[it.slot] = ItemSerialization.fromBytes(it.item);
                }
                final String finalOwnerDisplay = ownerDisplay;
                new BukkitRunnable() { @Override public void run() { openOnMain(player, vaultId, finalOwnerDisplay, action, contents, invSize, reopenCallback); } }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void openOnMain(Player player, UUID vaultId, String ownerDisplay, VaultAction action, ItemStack[] contents, int size, Runnable reopenCallback) {
        Component title = Component.text("Owner: ", NamedTextColor.WHITE)
                .append(Component.text(ownerDisplay, NamedTextColor.GOLD))
                .append(Component.text(" - " + action.name(), NamedTextColor.GRAY));

        Inventory inv = Bukkit.createInventory(null, size, title);
        for (int i = 0; i < Math.min(size, contents.length); i++) {
            ItemStack it = contents[i];
            if (it != null) inv.setItem(i, it.clone());
        }

        DynamicListener dyn = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId()).getDynamicListener();

        Listener listener = new Listener() {
            @EventHandler
            public void onClick(InventoryClickEvent event) {
                if (!event.getWhoClicked().getUniqueId().equals(player.getUniqueId())) return;
                if (event.getView().getTopInventory() != inv) return;
                switch (action) {
                    case VIEW -> event.setCancelled(true);
                    case COPY -> {
                        if (event.getClickedInventory() == inv) {
                            ItemStack current = event.getCurrentItem();
                            if (current != null) {
                                event.setCancelled(true);
                                ItemStack copy = current.clone();
                                Map<Integer, ItemStack> notFit = player.getInventory().addItem(copy);
                                if (!notFit.isEmpty()) {
                                    for (ItemStack leftover : notFit.values()) {
                                        if (leftover != null) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                                    }
                                }
                            }
                        }
                    }
                    case EDIT -> {
                        // allow edits
                    }
                }
            }
            @EventHandler
            public void onDrag(InventoryDragEvent event) {
                if (!event.getWhoClicked().getUniqueId().equals(player.getUniqueId())) return;
                if (event.getView().getTopInventory() != inv) return;
                if (action == VaultAction.VIEW) {
                    event.setCancelled(true);
                } else if (action == VaultAction.COPY) {
                    boolean affectsTop = event.getRawSlots().stream().anyMatch(slot -> slot < inv.getSize());
                    if (affectsTop) event.setCancelled(true);
                }
            }
            @EventHandler
            public void onClose(InventoryCloseEvent event) {
                if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
                if (event.getInventory() != inv) return;
                if (action == VaultAction.EDIT) {
                    ItemStack[] newContents = inv.getContents();
                    new BukkitRunnable() { @Override public void run() { saveToDb(vaultId, newContents); } }.runTaskAsynchronously(VaultStoragePlugin.getInstance());
                }
                dyn.stop();
                if (reopenCallback != null) {
                    Bukkit.getScheduler().runTask(VaultStoragePlugin.getInstance(), reopenCallback);
                }
            }
        };

        dyn.setListener(listener);
        dyn.start();
        player.openInventory(inv);
    }

    private void saveToDb(UUID vaultId, ItemStack[] contents) {
        VaultService vs = VaultStoragePlugin.getInstance().getVaultService();
        var existing = vs.listItems(vaultId);
        for (var row : existing) vs.removeItem(vaultId, row.slot);
        // Batch re-insert items to minimize DB round-trips
        List<VaultItemEntity> batch = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null) continue;
            VaultItemEntity vie = new VaultItemEntity();
            vie.vaultUuid = vaultId;
            vie.slot = i;
            vie.amount = it.getAmount();
            vie.item = ItemSerialization.toBytes(it);
            batch.add(vie);
        }
        if (!batch.isEmpty()) {
            vs.putItems(vaultId, batch);
        }
    }
}
