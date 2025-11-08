package net.democracycraft.vault.internal.service;

import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.internal.ui.VaultAction;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import net.democracycraft.vault.internal.util.listener.DynamicListener;
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

/**
 * Service to open a virtual inventory for a Vault with a specific action mode.
 * Uses VaultService to load/persist items asynchronously.
 */
public class VaultInventoryService {

    /** Opens a virtual inventory for the given vault id and action. */
    public void openVirtualInventory(Player player, UUID vaultId, VaultAction action) {
        openVirtualInventory(player, vaultId, action, null);
    }

    /** Opens a virtual inventory with a callback to run (on main thread) after it closes. */
    public void openVirtualInventory(Player player, UUID vaultId, VaultAction action, Runnable reopenCallback) {
        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable() {
            @Override public void run() {
                VaultService vs = plugin.getVaultService();
                var entityOpt = vs.get(vaultId);
                if (entityOpt.isEmpty()) {
                    new BukkitRunnable() { @Override public void run() { player.sendMessage("Vault not found."); } }.runTask(plugin);
                    return;
                }
                var items = vs.listItems(vaultId);
                int maxSlot = items.stream().mapToInt(it -> it.slot).max().orElse(-1);
                int invSize = Math.min(54, Math.max(9, ((Math.max(maxSlot + 1, 9) + 8) / 9) * 9));
                ItemStack[] contents = new ItemStack[invSize];
                Arrays.fill(contents, null);
                for (var it : items) {
                    if (it.slot >= 0 && it.slot < invSize) contents[it.slot] = ItemSerialization.fromBytes(it.item);
                }
                new BukkitRunnable() { @Override public void run() { openOnMain(player, vaultId, action, contents, invSize, reopenCallback); } }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void openOnMain(Player player, UUID vaultId, VaultAction action, ItemStack[] contents, int size, Runnable reopenCallback) {
        Inventory inv = Bukkit.createInventory(null, size, ChatColor.WHITE + "Vault " + vaultId + " - " + action.name());
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
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null) continue;
            vs.putItem(vaultId, i, it.getAmount(), ItemSerialization.toBytes(it));
        }
    }
}
