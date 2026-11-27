package com.minecraftcitiesnetwork.vaultstorage.capture;

import org.jetbrains.annotations.NotNull;

/**
 * The world, as a capture sees it. {@code T} is what is being captured: a block or a hanging entity on a server, a
 * plain value in a test. Every method is called on the main thread.
 */
public interface ContainerAccess<T> {

    /** Reads the target without changing it. */
    @NotNull ContainerSnapshot snapshot(@NotNull T target);

    /** Whether the target is still there, still of the same kind, and holds exactly the snapshot's items. */
    boolean stillMatches(@NotNull T target, @NotNull ContainerSnapshot snapshot);

    /** Removes the target from the world, with what it holds. */
    void remove(@NotNull T target);
}
