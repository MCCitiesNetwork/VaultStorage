package com.minecraftcitiesnetwork.vaultstorage.capture;

import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Painting;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

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
     * Whether the frame has no displayed item (the same notion of empty as
     * {@link ContainerBlocks#isEmpty} for blocks).
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
}
