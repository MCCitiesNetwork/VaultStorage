package com.minecraftcitiesnetwork.vaultstorage.capture;

import org.jetbrains.annotations.NotNull;

/**
 * The world, as a capture sees it. {@code T} is what is being captured: a block on a server, a
 * plain value in a test. Every method is called on the main thread.
 */
public interface ContainerAccess<T> {

    /** Reads the target without changing it. */
    @NotNull ContainerSnapshot snapshot(@NotNull T target);

    /** Whether the target is still the same kind of container holding exactly the snapshot's items. */
    boolean stillMatches(@NotNull T target, @NotNull ContainerSnapshot snapshot);

    /** Empties the target and removes it from the world. */
    void remove(@NotNull T target);
}
