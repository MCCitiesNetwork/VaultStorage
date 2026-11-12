package net.democracycraft.vault.internal.util.hanging;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.democracycraft.vault.api.service.VaultService;
import net.democracycraft.vault.internal.database.entity.VaultEntity;
import net.democracycraft.vault.internal.database.entity.VaultItemEntity;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Helpers for hanging capture: supporting block resolution, stacks to store, and merge target selection.
 */
public final class HangingVaultSupport {

    /**
     * Max slots when merging hangings into an existing vault (27, one single chest). Tail append only; does not use a 54-slot grid.
     */
    public static final int MAX_VAULT_SLOTS = 27;

    private HangingVaultSupport() {}

    /**
     * Block the hanging is attached to (Bolt and WorldGuard context for policy).
     * @param hanging entity to resolve
     * @return supporting block
     */
    public static @NotNull Block resolveSupportingBlock(@NotNull Hanging hanging) {
        return hanging.getLocation().getBlock().getRelative(hanging.getAttachedFace());
    }

    /**
     * Whether the frame has no displayed item (same empty notion as
     * {@link net.democracycraft.vault.internal.service.VaultCaptureService#isContainerEmpty} for blocks).
     * Capture treats this as unlock-only on the supporting block.
     * @param frame item frame to inspect
     * @return true if there is nothing to treat as contents
     */
    public static boolean isItemFrameDisplayEmpty(@NotNull ItemFrame frame) {
        ItemStack inner = frame.getItem();
        if (inner == null) return true;
        if (inner.getType() == Material.AIR) return true;
        return inner.getAmount() <= 0;
    }

    /**
     * Builds stacks to persist: item frame plus inner item if any, or one painting with variant data.
     * @param hanging item frame or painting
     * @return stacks to write to the vault (empty if unsupported type)
     */
    public static @NotNull List<ItemStack> itemStacksFrom(@NotNull Hanging hanging) {
        List<ItemStack> out = new ArrayList<>(2);
        if (hanging instanceof ItemFrame frame) {
            Material mat = frame instanceof GlowItemFrame ? Material.GLOW_ITEM_FRAME : Material.ITEM_FRAME;
            out.add(new ItemStack(mat, 1));
            ItemStack inner = frame.getItem();
            if (inner != null && inner.getType() != Material.AIR && inner.getAmount() > 0) {
                out.add(inner.clone());
            }
            return out;
        }
        if (hanging instanceof Painting painting) {
            ItemStack stack = ItemStack.of(Material.PAINTING, 1);
            stack.setData(DataComponentTypes.PAINTING_VARIANT, painting.getArt());
            out.add(stack);
            return out;
        }
        return List.of();
    }

    /** Vault id and first slot index for a batch append. */
    public record TargetVault(@NotNull UUID vaultUuid, int startSlot) {}

    /**
     * Oldest owned vault first (by {@link VaultEntity#createdAtEpochMillis}), first with enough tail slots.
     * @param vaultService vault access
     * @param ownerUuid vault owner
     * @param neededSlots contiguous slots required
     * @return target vault and start slot, or empty if none fit
     */
    public static @NotNull Optional<TargetVault> findFirstVaultWithSpace(
            @NotNull VaultService vaultService,
            @NotNull UUID ownerUuid,
            int neededSlots
    ) {
        if (neededSlots <= 0 || neededSlots > MAX_VAULT_SLOTS) {
            return Optional.empty();
        }
        List<VaultEntity> owned = new ArrayList<>(vaultService.listByOwner(ownerUuid));
        owned.sort(Comparator.comparingLong(e -> e.createdAtEpochMillis != null ? e.createdAtEpochMillis : Long.MAX_VALUE));
        for (VaultEntity ve : owned) {
            List<VaultItemEntity> items = vaultService.listItems(ve.uuid);
            int maxSlot = items.stream().mapToInt(it -> it.slot).max().orElse(-1);
            if (maxSlot + neededSlots <= MAX_VAULT_SLOTS - 1) {
                return Optional.of(new TargetVault(ve.uuid, maxSlot + 1));
            }
        }
        return Optional.empty();
    }
}
