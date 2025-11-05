package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.data.Dto;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.listener.DynamicListener;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Child dialog that lets a player choose an action (view, edit, copy) for a vault.
 * Uses VaultService asynchronously to avoid blocking the main thread.
 */
public class VaultActionMenu extends ChildMenuImp {

    private static final String KEY_ACTION = "ACTION";

    private final UUID vaultId;

    /** Configurable menu texts. */
    public static class Config implements Dto, java.io.Serializable {
        /** Dialog title. */
        public String title = "<gold><bold>Vault Action</bold></gold>";
        /** Label for the action selector. */
        public String actionLabel = "<gray>Select action</gray>";
        /** Button to open the chosen action. */
        public String openBtn = "<green><bold>Open Inventory</bold></green>";
        /** Button to place the vault block back into the world. */
        public String placeBtn = "<yellow><bold>Place Block</bold></yellow>";
        /** Back button to return to list. */
        public String backBtn = "<red><bold>Back</bold></red>";
        /** Message when target vault is not found. */
        public String notFound = "<red><bold>Vault not found.</bold></red>";
        /** Message when no actions are available due to permissions. */
        public String noActions = "<red><bold>No actions available.</bold></red>";
        /** Label format for action options (placeholder %action%). */
        public String actionOptionFormat = "<white>%action%</white>";
        /** Inventory title when opening virtual inventory. Placeholders: %id% %action% */
        public String inventoryTitle = "<white>Vault %id% - %action%</white>";
        /** Loading message for chat when preparing inventory. */
        public String loading = "<gray>Loading vault...</gray>";
        /** Saved message after edit. */
        public String saved = "<green>Vault saved.</green>";
        /** Placing start message. */
        public String placing = "<gray>Placing block...</gray>";
        /** Placement success prefix. Placeholder %msg%. */
        public String placeOk = "<green>%msg%</green>";
        /** Placement failure prefix. Placeholder %msg%. */
        public String placeFail = "<red>%msg%</red>";
    }

    private static final String HEADER = String.join("\n",
            "VaultActionMenu configuration.",
            "Fields accept MiniMessage or plain strings.",
            "Placeholders for actionOptionFormat:",
            "- %action% -> formatted action name (View/Copy/Edit)",
            "Placeholders for inventoryTitle:",
            "- %id% -> vault UUID",
            "- %action% -> selected action (View/Copy/Edit)",
            "Placeholders for placement messages:",
            "- %msg% -> result message from placement service"
    );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultActionMenu", DataFolder.MENUS, HEADER);
    /** Ensures the YAML file for this menu exists by creating defaults if missing. */
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }
    private static VaultActionMenu.Config cfg() { return YML.loadOrCreate(VaultActionMenu.Config::new); }

    public VaultActionMenu(Player player, ParentMenuImp parent, UUID vaultId) {
        super(player, parent, "vault_action_" + vaultId);
        this.vaultId = vaultId;
        setDialog(build());
    }

    private Dialog build() {
        Config cfg = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(MiniMessageUtil.parseOrPlain(cfg.title));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        // Build SingleOption with available actions filtered by permissions
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        Player p = getPlayer();
        String firstValue = null;
        for (VaultAction a : VaultAction.values()) {
            if (p.hasPermission(a.permission())) {
                String value = a.name().toLowerCase(Locale.ROOT);
                boolean selected = firstValue == null; // preselect first permitted
                if (firstValue == null) firstValue = value;
                Map<String,String> ph = Map.of("%action%", cap(a.name()));
                entries.add(SingleOptionDialogInput.OptionEntry.create(value, MiniMessageUtil.parseOrPlain(cfg.actionOptionFormat, ph), selected));
            }
        }
        if (entries.isEmpty()) {
            builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.noActions)));
            builder.button(MiniMessageUtil.parseOrPlain(cfg.backBtn), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), "").open());
            return builder.build();
        }

        builder.addInput(DialogInput.singleOption(KEY_ACTION, MiniMessageUtil.parseOrPlain(cfg.actionLabel), entries).build());

        String defaultValue = firstValue; // fallback when dialog returns null
        builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(cfg.openBtn), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            String selected = Optional.ofNullable(response.getText(KEY_ACTION)).orElse(defaultValue);
            VaultAction action = parseAction(selected);
            actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.loading));
            openInventoryAsync(actor, action, cfg);
        });

        // Place block button (does not require selecting an inventory action)
        builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(cfg.placeBtn), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.placing));
            VaultStoragePlugin.getInstance().getPlacementService().placeFromDatabaseAsync(vaultId, res -> {
                Map<String,String> ph = Map.of("%msg%", res.message());
                if (res.success()) actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.placeOk, ph));
                else actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.placeFail, ph));
            });
        });

        builder.button(MiniMessageUtil.parseOrPlain(cfg.backBtn), ctx -> new VaultListMenu(ctx.player(), (ParentMenuImp) getParentMenu(), "").open());
        return builder.build();
    }

    private static VaultAction parseAction(String s) {
        try { return VaultAction.valueOf(s.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ex) { return VaultAction.VIEW; }
    }

    private static String cap(String s) { return s.substring(0,1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT); }

    private void openInventoryAsync(Player player, VaultAction action, Config cfg) {
        var plugin = VaultStoragePlugin.getInstance();
        new BukkitRunnable() {
            @Override public void run() {
                VaultService vs = plugin.getVaultService();
                var entityOpt = vs.get(vaultId);
                if (entityOpt.isEmpty()) {
                    new BukkitRunnable() { @Override public void run() {
                        player.sendMessage(MiniMessageUtil.parseOrPlain(cfg.notFound));
                        new VaultListMenu(player, (ParentMenuImp) getParentMenu(), "").open();
                    }}.runTask(plugin);
                    return;
                }
                var items = vs.listItems(vaultId);
                // Build ItemStacks array
                int maxSlot = items.stream().mapToInt(it -> it.slot).max().orElse(-1);
                int invSize = Math.min(54, Math.max(9, ((Math.max(maxSlot + 1, 9) + 8) / 9) * 9));
                ItemStack[] contents = new ItemStack[invSize];
                Arrays.fill(contents, null);
                for (var it : items) {
                    if (it.slot >= 0 && it.slot < invSize) contents[it.slot] = ItemSerialization.fromBytes(it.item);
                }
                new BukkitRunnable() { @Override public void run() { openInventoryOnMain(player, action, cfg, contents, invSize); } }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    private void openInventoryOnMain(Player player, VaultAction action, Config cfg, ItemStack[] contents, int size) {
        Map<String,String> ph = Map.of("%id%", vaultId.toString(), "%action%", cap(action.name()));
        Inventory inv = Bukkit.createInventory(null, size, MiniMessageUtil.parseOrPlain(cfg.inventoryTitle, ph));
        for (int i = 0; i < Math.min(size, contents.length); i++) {
            ItemStack it = contents[i];
            if (it != null) inv.setItem(i, it.clone());
        }

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
                    new BukkitRunnable() { @Override public void run() { saveInventoryToDb(newContents); } }.runTaskAsynchronously(VaultStoragePlugin.getInstance());
                    player.sendMessage(MiniMessageUtil.parseOrPlain(cfg.saved));
                }
                dyn.stop();
            }
        };

        dyn.setListener(listener);
        dyn.start();
        player.openInventory(inv);
    }

    private void saveInventoryToDb(ItemStack[] contents) {
        VaultService vs = VaultStoragePlugin.getInstance().getVaultService();
        // Remove existing items first
        var existing = vs.listItems(vaultId);
        for (var row : existing) {
            vs.removeItem(vaultId, row.slot);
        }
        // Insert new items
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null) continue;
            vs.putItem(vaultId, i, it.getAmount(), ItemSerialization.toBytes(it));
        }
    }
}
