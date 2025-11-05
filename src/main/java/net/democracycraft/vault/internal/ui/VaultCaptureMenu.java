package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.service.BoltService;
import net.democracycraft.vault.api.service.WorldGuardService;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.service.VaultCaptureService;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import net.democracycraft.vault.internal.util.config.DataFolder;
import net.democracycraft.vault.internal.util.item.ItemSerialization;
import net.democracycraft.vault.internal.util.minimessage.MiniMessageUtil;
import net.democracycraft.vault.internal.util.yml.AutoYML;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;

import java.util.*;

/**
 * Vault capture UI shown when the command is executed.
 */
public class VaultCaptureMenu extends ParentMenuImp {

    /** Configurable menu texts. */
    public static class Config implements net.democracycraft.vault.api.data.Dto, java.io.Serializable {
        /** Dialog title. Supports %player% placeholder. */
        public String title = "<gold><bold>Vault Capture</bold></gold>";
        /** Instruction line explaining how to start capture. Supports %player%. */
        public String instruction = "<gray>Click any container block to convert it into a Vault. The block will be removed and its items captured.</gray>";
        /** Instruction line explaining how to cancel. Supports %player%. */
        public String cancelHint = "<gray>Left-click anywhere to cancel.</gray>";
        /** Button to start capture mode. Supports %player%. */
        public String startBtn = "<green><bold>Start Capture</bold></green>";
        /** Button to browse vaults. Supports %player%. */
        public String browseBtn = "<green>Browse Vaults</green>";
        /** Button to close dialog. Supports %player%. */
        public String closeBtn = "<red><bold>Close</bold></red>";
        /** Chat message when capture is cancelled. Supports %player%. */
        public String captureCancelled = "Capture cancelled.";
        /** Chat message when target is not a container. Supports %player%. */
        public String notAContainer = "That block is not a container.";
        /** Chat message when capture succeeds. Supports %player%. */
        public String capturedOk = "Vault captured.";
        /** Label for region id input. */
        public String regionInputLabel = "<gray>Region ID</gray>";
        /** Button to trigger a scan of a region. */
        public String scanRegionBtn = "<yellow>Scan Region</yellow>";
        /** Message header when reporting mismatched protections. */
        public String scanHeader = "<gold><bold>Region scan for %region%</bold> (<white>%count%</white> mismatches)";
        /** Per-entry format when reporting mismatched blocks. Placeholders: %x% %y% %z% %owner% */
        public String scanEntry = "<gray>- </gray><white>(%x%, %y%, %z%)</white> <gray>owner:</gray> <white>%owner%</white>";
        /** Message when no mismatches. */
        public String scanNone = "<gray>No mismatched protected containers in this region.</gray>";
        /** Message when the region input is empty. */
        public String scanNeedRegion = "<red>Please enter a region ID.</red>";
        /** Message when WorldGuard or Bolt services are not available. */
        public String scanServicesMissing = "<red>Scanning is unavailable: required services are not ready.</red>";
    }

    private static final String HEADER = String.join("\n",
            "VaultCaptureMenu configuration.",
            "Fields accept MiniMessage or plain strings.",
            "Placeholders available:",
            "- %player% -> actor/player name",
            "- %region% -> region id",
            "- %count% -> result count",
            "- %x% %y% %z% %owner% -> per-entry values"
    );

    private static final AutoYML<Config> YML = AutoYML.create(Config.class, "VaultCaptureMenu", DataFolder.MENUS, HEADER);
    private static Config cfg() { return YML.loadOrCreate(Config::new); }
    /** Ensures the YAML file for this menu exists by creating defaults if missing. */
    public static void ensureConfig() { YML.loadOrCreate(Config::new); }

    /**
     * Creates a new capture menu for the given player.
     * @param player the player who will interact with the dialog
     */
    public VaultCaptureMenu(Player player) {
        super(player, "vault_capture");
        setDialog(build());
    }

    /**
     * Builds the dialog structure and associated actions.
     * @return the constructed dialog instance
     */
    private Dialog build() {
        Config cfg = cfg();
        AutoDialog.Builder builder = getAutoDialogBuilder();
        Map<String,String> phSelf = Map.of("%player%", getPlayer().getName());
        builder.title(MiniMessageUtil.parseOrPlain(cfg.title, phSelf));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.instruction, phSelf)));
        builder.addBody(DialogBody.plainMessage(MiniMessageUtil.parseOrPlain(cfg.cancelHint, phSelf)));

        // Start capture mode: installs a dynamic listener for the next relevant click, then closes the dialog
        builder.button(MiniMessageUtil.parseOrPlain(cfg.startBtn, phSelf), ctx -> {
            Player actor = ctx.player();
            VaultSessionManager.Session session = VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId());
            session.getDynamicListener().setListener(new Listener() {
                @EventHandler
                public void onInteract(PlayerInteractEvent event) {
                    if (!event.getPlayer().getUniqueId().equals(actor.getUniqueId())) return;
                    Action action = event.getAction();
                    // Cancel with any left click (air or block)
                    if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                        session.getDynamicListener().stop();
                        event.getPlayer().sendMessage(MiniMessageUtil.parseOrPlain(cfg.captureCancelled, Map.of("%player%", event.getPlayer().getName())));
                        new VaultCaptureMenu(actor).open();
                        return;
                    }
                    // Proceed only on right-click on a block
                    if (action != Action.RIGHT_CLICK_BLOCK) return;
                    Block block = event.getClickedBlock();
                    if (block == null) return;
                    session.getDynamicListener().stop();
                    if (!(block.getState() instanceof Container)) {
                        event.getPlayer().sendMessage(MiniMessageUtil.parseOrPlain(cfg.notAContainer, Map.of("%player%", event.getPlayer().getName())));
                        new VaultCaptureMenu(actor).open();
                        return;
                    }
                    // Use domain service to capture (main thread)
                    VaultCaptureService captureService = VaultStoragePlugin.getInstance().getCaptureService();
                    VaultImp vault = captureService.captureFromBlock(actor, block);
                    // Persist asynchronously with VaultService
                    var plugin = VaultStoragePlugin.getInstance();
                    new BukkitRunnable() {
                        @Override public void run() {
                            var vs = plugin.getVaultService();
                            UUID worldId = block.getWorld().getUID();
                            UUID owner = actor.getUniqueId();
                            // Avoid duplicates: delete existing vault at same location if present
                            var existing = vs.findByLocation(worldId, block.getX(), block.getY(), block.getZ());
                            if (existing != null) {
                                vs.delete(existing.uuid);
                            }
                            UUID newId = vs.createVault(worldId, block.getX(), block.getY(), block.getZ(), owner,
                                    vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                    vault.blockDataString());
                            List<ItemStack> items = vault.contents();
                            for (int i = 0; i < items.size(); i++) {
                                ItemStack it = items.get(i);
                                if (it == null) continue;
                                vs.putItem(newId, i, it.getAmount(), ItemSerialization.toBytes(it));
                            }
                            new BukkitRunnable() {
                                @Override public void run() {
                                    var dto = new VaultDtoImp(newId, owner, List.of(),
                                            vault.blockMaterial() == null ? null : vault.blockMaterial().name(),
                                            null, System.currentTimeMillis());
                                    VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId()).setLastVaultDto(dto);
                                    actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.capturedOk, Map.of("%player%", actor.getName())));
                                    new VaultCaptureMenu(actor).open();
                                }
                            }.runTask(plugin);
                        }
                    }.runTaskAsynchronously(plugin);
                }
            });
            session.getDynamicListener().start();
            // Close the dialog while waiting for the in-game click
            actor.closeDialog();
        });

        // Region scan input and button
        builder.addInput(DialogInput.text("REGION_ID", MiniMessageUtil.parseOrPlain(cfg.regionInputLabel)).labelVisible(true).build());
        builder.buttonWithPlayer(MiniMessageUtil.parseOrPlain(cfg.scanRegionBtn), null, java.time.Duration.ofMinutes(5), 1, (actor, response) -> {
            String regionId = Optional.ofNullable(response.getText("REGION_ID")).orElse("").trim();
            if (regionId.isEmpty()) {
                actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.scanNeedRegion));
                return;
            }
            World world = actor.getWorld();
            WorldGuardService wgs = VaultStoragePlugin.getInstance().getWorldGuardService();
            BoltService bolt = VaultStoragePlugin.getInstance().getBoltService();
            if (wgs == null || bolt == null) {
                actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.scanServicesMissing));
                return;
            }
            var regions = wgs.getRegionsIn(world);
            var target = regions.stream().filter(r -> r.id().equalsIgnoreCase(regionId)).findFirst();
            if (target.isEmpty()) {
                actor.sendMessage(MiniMessageUtil.parseOrPlain("<red>Region not found: %region%</red>", Map.of("%region%", regionId)));
                return;
            }
            var reg = target.get();
            BoundingBox box = reg.boundingBox();
            List<Block> blocks = bolt.getProtectedBlocksIn(box, world);
            List<String> lines = new ArrayList<>();
            for (org.bukkit.block.Block b : blocks) {
                UUID owner = bolt.getOwner(b);
                if (owner == null) continue;
                if (reg.isOwner(owner) || reg.isMember(owner)) continue;
                String name = Optional.ofNullable(Bukkit.getOfflinePlayer(owner).getName()).orElse(owner.toString());
                Map<String,String> ph = Map.of("%x%", String.valueOf(b.getX()), "%y%", String.valueOf(b.getY()), "%z%", String.valueOf(b.getZ()), "%owner%", name);
                lines.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(MiniMessageUtil.parseOrPlain(cfg.scanEntry, ph)));
            }
            Map<String,String> ph = Map.of("%region%", reg.id(), "%count%", String.valueOf(lines.size()));
            actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.scanHeader, ph));
            if (lines.isEmpty()) {
                actor.sendMessage(MiniMessageUtil.parseOrPlain(cfg.scanNone));
            } else {
                for (String line : lines) actor.sendMessage(net.kyori.adventure.text.Component.text(line));
            }
        });

        builder.button(MiniMessageUtil.parseOrPlain(cfg.browseBtn, phSelf), ctx -> new VaultListMenu(ctx.player(), this, "").open());
        builder.button(MiniMessageUtil.parseOrPlain(cfg.closeBtn, phSelf), ctx -> {});
        return builder.build();
    }
}
