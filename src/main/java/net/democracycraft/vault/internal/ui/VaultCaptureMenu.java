package net.democracycraft.vault.internal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.democracycraft.vault.VaultStoragePlugin;
import net.democracycraft.vault.api.ui.AutoDialog;
import net.democracycraft.vault.internal.data.VaultDtoImp;
import net.democracycraft.vault.internal.mappable.VaultImp;
import net.democracycraft.vault.internal.session.VaultSessionManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Vault capture UI shown when the command is executed.
 */
public class VaultCaptureMenu extends ParentMenuImp {

    public VaultCaptureMenu(Player player) {
        super(player, "vault_capture");
        setDialog(build());
    }

    private Dialog build() {
        AutoDialog.Builder builder = getAutoDialogBuilder();
        builder.title(miniMessage("<gold><bold>Vault Capture</bold></gold>"));
        builder.canCloseWithEscape(true);
        builder.afterAction(DialogBase.DialogAfterAction.CLOSE);

        builder.addBody(DialogBody.plainMessage(miniMessage("<gray>Click any container block to convert it into a Vault. The block will be removed and its items captured.</gray>")));
        builder.addBody(DialogBody.plainMessage(miniMessage("<gray>Left-click anywhere to cancel.</gray>")));

        // Start capture mode: installs a dynamic listener for the next relevant click, then closes the dialog
        builder.button(miniMessage("<green><bold>Start Capture</bold></green>"), ctx -> {
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
                        actor.sendMessage("Capture cancelled.");
                        new VaultCaptureMenu(actor).open();
                        return;
                    }
                    // Proceed only on right-click on a block
                    if (action != Action.RIGHT_CLICK_BLOCK) return;
                    Block block = event.getClickedBlock();
                    if (block == null) return;
                    session.getDynamicListener().stop();
                    if (!(block.getState() instanceof Container container)) {
                        actor.sendMessage("That block is not a container.");
                        new VaultCaptureMenu(actor).open();
                        return;
                    }
                    Inventory captureInv;
                    if (container instanceof Chest chest) {
                        captureInv = chest.getBlockInventory();
                    } else {
                        captureInv = container.getInventory();
                    }
                    List<ItemStack> stacks = Arrays.stream(captureInv.getContents())
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    // Clear only the captured inventory and remove the clicked block
                    captureInv.clear();
                    // Capture metadata before removing
                    var material = block.getType();
                    var location = block.getLocation();
                    var when = Instant.now();
                    block.setType(Material.AIR);

                    UUID vaultId = UUID.randomUUID();
                    UUID owner = actor.getUniqueId();
                    var vault = new VaultImp(owner, vaultId, stacks, material, location, when);
                    var dto = (VaultDtoImp) vault.toDto();
                    VaultStoragePlugin.getInstance().getSessionManager().getOrCreate(actor.getUniqueId()).setLastVaultDto(dto);
                    VaultStoragePlugin.getInstance().getSessionManager().addVault(vault);

                    actor.sendMessage("Vault captured.");
                    new VaultCaptureMenu(actor).open();
                }
            });
            session.getDynamicListener().start();
            // Close the dialog while waiting for the in-game click
            actor.closeDialog();
        });

        builder.button(miniMessage("<aqua>Show last Vault JSON</aqua>"), ctx -> {
            var manager = net.democracycraft.vault.VaultStoragePlugin.getInstance().getSessionManager();
            var session = manager.get(ctx.player().getUniqueId());
            if (session == null || session.getLastVaultDto() == null) {
                ctx.player().sendMessage("No captured vault yet.");
            } else {
                String json = manager.toJson(session.getLastVaultDto());
                ctx.player().sendMessage(json);
            }
        });
        builder.button(miniMessage("<green>Browse Vaults</green>"), ctx -> new VaultListMenu(ctx.player(), this, "").open());
        builder.button(miniMessage("<red><bold>Close</bold></red>"), ctx -> {});
        return builder.build();
    }
}
