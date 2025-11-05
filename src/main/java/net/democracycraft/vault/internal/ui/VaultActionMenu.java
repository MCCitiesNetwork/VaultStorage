package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.convertible.Vault;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.util.DynamicListener;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.Serializable;
import java.util.*;

/**
 * Child dialog that lets a player choose an action (view, edit, copy) for a specific Vault
 * and then opens a virtual inventory accordingly, managing interactions via a DynamicListener.
 */
public class VaultActionMenu extends ChildMenuImp {

    private static final String KEY_ACTION = "ACTION";

    private final UUID vaultId;

    /** Configurable menu texts. */
    public static class Config implements Dto {
        public String title = "<gold><bold>Vault Action</bold></gold>";
        public String actionLabel = "<gray>Select action</gray>";
        public String openBtn = "<green><bold>Open Inventory</bold></green>";
        public String backBtn = "<red><bold>Back</bold></red>";
        public String notFound = "<red><bold>Vault not found.</bold></red>";
        public String inventoryTitle = "<white>Vault %id% - %action%</white>";
    }

    /**
     * @param player viewer
     * @param parent parent menu
     * @param vaultId target vault identifier
     */
    public VaultActionMenu(Player player, ParentMenuImp parent, UUID vaultId) {
        super(player, parent, "vault_action_" + vaultId);
        this.vaultId = vaultId;
        setDialog(build());
    }

    private Dialog build() {
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Config cfg = new Config();
        builder.title(miniMessage(cfg.title));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        Vault vault = VaultStoragePlugin.getInstance().getVaultStore().get(vaultId);
        if (vault == null) {
            builder.addBody(DialogBody.plainMessage(miniMessage(cfg.notFound)));
            builder.button(miniMessage(cfg.backBtn), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), "").open());
            return builder.build();
        }

        // Build SingleOption with available actions filtered by permissions
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        Player p = getPlayer();
        String firstValue = null;
        for (VaultAction a : VaultAction.values()) {
            if (p.hasPermission(a.permission())) {
                String value = a.name().toLowerCase(Locale.ROOT);
                boolean selected = firstValue == null; // preselect first permitted
                if (firstValue == null) firstValue = value;
                entries.add(SingleOptionDialogInput.OptionEntry.create(value, Component.text(cap(a.name())), selected));
            }
        }
        if (entries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(miniMessage("<red><bold>No actions available.</bold></red>")));
            builder.button(miniMessage(cfg.backBtn), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), "").open());
            return builder.build();
        }

        builder.addInput(DialogInput.singleOption(KEY_ACTION, miniMessage(cfg.actionLabel), entries).build());

        String defaultValue = firstValue; // fallback when dialog returns null
        builder.buttonWithPlayer(miniMessage(cfg.openBtn), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            String selected = Optional.ofNullable(response.getText(KEY_ACTION)).orElse(defaultValue);
            VaultAction action = parseAction(selected);
            openInventoryFor(actor, vault, action, cfg);
        });

        // Place button (restore block at original location)
        if (p.hasPermission("vault.action.place")) {
            builder.button(miniMessage("<yellow><bold>Place</bold></yellow>"), ctx -> {
                Location loc = vault.blockLocation();
                Material mat = vault.blockMaterial();
                if (loc == null || mat == null) { ctx.player().sendMessage(miniMessage("<red><bold>Missing original location or material.</bold></red>")); return; }
                World world = loc.getWorld();
                if (world == null) { ctx.player().sendMessage(miniMessage("<red><bold>World not available.</bold></red>")); return; }
                Chunk chunk = world.getChunkAt(loc);
                if (!chunk.isLoaded()) chunk.load();
                Block block = world.getBlockAt(loc);
                if (block.getType() != Material.AIR && block.getType() != mat) {
                    ctx.player().sendMessage(miniMessage("<red><bold>Target block not empty.</bold></red>"));
                    return;
                }
                block.setType(mat, true);
                if (block.getState() instanceof Container container) {
                    Inventory targetInv;
                    if (container instanceof Chest chest) targetInv = chest.getBlockInventory(); else targetInv = container.getInventory();
                    targetInv.clear();
                    List<ItemStack> items = vault.contents();
                    int size = targetInv.getSize();
                    int i = 0;
                    for (ItemStack it : items) {
                        if (it == null) continue;
                        if (i >= size) { world.dropItemNaturally(loc, it.clone()); continue; }
                        targetInv.setItem(i++, it.clone());
                    }
                    ctx.player().sendMessage(miniMessage("<green><bold>Vault placed.</bold></green>"));
                } else {
                    ctx.player().sendMessage(miniMessage("<yellow>Block placed, but it has no inventory.</yellow>"));
                }
            });
        }

        builder.button(miniMessage(cfg.backBtn), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), "").open());
        return builder.build();
    }

    private static VaultAction parseAction(String s) {
        try { return VaultAction.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { return VaultAction.VIEW; }
    }

    private static String cap(String s) { return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT); }

    private void openInventoryFor(Player player, Vault vault, VaultAction action, Config cfg) {
        // Build inventory size as nearest multiple of 9 up to 54
        List<ItemStack> items = vault.contents();
        int size = Math.min(54, Math.max(9, ((items.size() + 8) / 9) * 9));
        String title = apply(cfg.inventoryTitle, Map.of("%id%", vault.uniqueIdentifier().toString(), "%action%", cap(action.name())));
        Inventory inv = Bukkit.createInventory(null, size, miniMessage(title));
        for (int i = 0; i < Math.min(size, items.size()); i++) {
            ItemStack it = items.get(i);
            if (it != null) inv.setItem(i, it.clone());
        }

        // Prepare dynamic listener
        VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(player.getUniqueId());
        DynamicListener dyn = session.getDynamicListener();

        Listener listener = new Listener() {
            @EventHandler
            public void onClick(InventoryClickEvent event) {
                if (!event.getWhoClicked().getUniqueId().equals(player.getUniqueId())) return;
                if (event.getView().getTopInventory() != inv) return;
                switch (action) {
                    case VIEW -> event.setCancelled(true);
                    case COPY -> {
                        // Give a copy without altering the top inventory
                        if (event.getClickedInventory() == inv) {
                            ItemStack current = event.getCurrentItem();
                            if (current != null) {
                                event.setCancelled(true);
                                ItemStack copy = current.clone();
                                Map<Integer, ItemStack> notFit = player.getInventory().addItem(copy);
                                if (!notFit.isEmpty()) {
                                    // Drop leftovers at player's feet
                                    for (ItemStack leftover : notFit.values()) {
                                        if (leftover != null) player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                                    }
                                }
                            }
                        }
                    }
                    case EDIT -> {
                        // allow edits; no cancel
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
                    // Prevent modifying the top inventory while allowing player inventory drags
                    boolean affectsTop = event.getRawSlots().stream().anyMatch(slot -> slot < inv.getSize());
                    if (affectsTop) event.setCancelled(true);
                }
            }
            @EventHandler
            public void onClose(InventoryCloseEvent event) {
                if (!event.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
                if (event.getInventory() != inv) return;
                // Persist only on EDIT
                if (action == VaultAction.EDIT) {
                    List<ItemStack> newContents = Arrays.asList(inv.getContents());
                    VaultImp updated = new VaultImp(
                            vault.ownerUniqueIdentifier(),
                            vault.uniqueIdentifier(),
                            newContents,
                            vault.blockMaterial(),
                            vault.blockLocation(),
                            vault.vaultedAt()
                    );
                    VaultStoragePlugin.getInstance().getSessionManager().updateVault(updated);
                }
                dyn.stop();
            }
        };

        dyn.setListener(listener);
        dyn.start();
        player.openInventory(inv);
    }

    private static String apply(String text, Map<String, String> ph) {
        String s = text == null ? "" : text;
        if (ph != null) {
            for (var e : ph.entrySet()) s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }
}
