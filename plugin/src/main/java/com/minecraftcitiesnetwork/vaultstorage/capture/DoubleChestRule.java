package com.minecraftcitiesnetwork.vaultstorage.capture;

import org.jetbrains.annotations.NotNull;

/** What a capture does with the block it was pointed at. Names no Bukkit type. */
public final class DoubleChestRule {

    /** What to do with the clicked block. */
    public enum Action {
        /** Nothing to store: remove the protection and leave the block. */
        UNPROTECT,
        /** The clicked half is empty and the other is not: do nothing and say so. */
        OTHER_HALF_FIRST,
        /** Store the block's items as a vault and remove it. */
        CAPTURE
    }

    private DoubleChestRule() {
    }

    /**
     * @param container      whether the block is a container at all
     * @param targetEmpty    whether the clicked block holds no items
     * @param hasOtherHalf   whether it is one half of a double chest
     * @param otherHalfEmpty whether that other half holds no items
     */
    public static @NotNull Action decide(boolean container, boolean targetEmpty, boolean hasOtherHalf,
                                         boolean otherHalfEmpty) {
        if (!container) {
            return Action.UNPROTECT;
        }
        if (!targetEmpty) {
            return Action.CAPTURE;
        }
        // Bolt keeps one protection for both halves, so unprotecting an empty half would also
        // unprotect a full one.
        return hasOtherHalf && !otherHalfEmpty ? Action.OTHER_HALF_FIRST : Action.UNPROTECT;
    }
}
