package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Blocks another plugin keeps on a container and that go when the container goes: a shop's
 * sign, for one. The host knows no such plugin. A module adds one of these for the plugin it
 * knows; with none added, nothing is attached to anything.
 */
public interface ContainerAttachments {

    /** Whether the block is one of this plugin's attachments. */
    boolean isAttachment(@Nullable Block block);

    /** This plugin's attachments on the container; empty when it has none. */
    @NotNull List<Block> attachedTo(@Nullable Block container);

    /**
     * Removes an attachment the way its plugin would, so the plugin's own records follow. Main
     * thread.
     *
     * @param actor who is removing it, or null for an automated capture
     * @return true if the block was an attachment and was removed
     */
    boolean remove(@Nullable Player actor, @Nullable Block attachment);
}
