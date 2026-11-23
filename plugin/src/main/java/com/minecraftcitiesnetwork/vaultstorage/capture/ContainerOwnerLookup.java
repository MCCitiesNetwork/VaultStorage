package com.minecraftcitiesnetwork.vaultstorage.capture;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Whose a container is, for a container nobody has locked. A source may have to read a
 * database, so the answer comes later and is remembered for a short while: the capture policy
 * and the action bar run on the main thread and can only use what is already known.
 *
 * <p>Main thread only.
 */
public final class ContainerOwnerLookup {

    /** Long enough to cover looking at a container and then clicking it. */
    private static final long REMEMBER_MILLIS = 30_000L;
    private static final int PRUNE_ABOVE = 256;

    private record Key(@NotNull UUID world, int x, int y, int z) {
        static @NotNull Key of(@NotNull Block block) {
            return new Key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private record Answer(@Nullable UUID owner, long until) {
    }

    private final @NotNull Supplier<ContainerOwnerSource> sources;
    private final @NotNull Executor mainThread;
    private final Map<Key, Answer> answers = new HashMap<>();

    /** @param sources read at each lookup: modules add and remove sources while the plugin runs */
    public ContainerOwnerLookup(@NotNull Supplier<ContainerOwnerSource> sources, @NotNull Executor mainThread) {
        this.sources = sources;
        this.mainThread = mainThread;
    }

    /** The owner found by an earlier {@link #ownerOf}, or null when none is known. */
    public @Nullable UUID known(@NotNull Block container) {
        Answer answer = this.answers.get(Key.of(container));
        return answer != null && answer.until() > System.currentTimeMillis() ? answer.owner() : null;
    }

    /**
     * The player the container belongs to. Already complete when there is nothing to ask or a
     * recent answer is remembered. Otherwise it completes on the main thread. Never completes
     * exceptionally.
     */
    public @NotNull CompletableFuture<Optional<UUID>> ownerOf(@NotNull Block container) {
        Key key = Key.of(container);
        Answer remembered = this.answers.get(key);
        if (remembered != null && remembered.until() > System.currentTimeMillis()) {
            return CompletableFuture.completedFuture(Optional.ofNullable(remembered.owner()));
        }
        CompletableFuture<Optional<UUID>> asked = this.sources.get().ownerOf(container)
                .exceptionally(failure -> Optional.empty());
        if (asked.isDone()) {
            // Answered without waiting. An empty answer is not remembered: there was nothing to ask.
            Optional<UUID> owner = asked.join();
            owner.ifPresent(found -> remember(key, found));
            return CompletableFuture.completedFuture(owner);
        }
        return asked.thenApplyAsync(owner -> {
            remember(key, owner.orElse(null));
            return owner;
        }, this.mainThread)
                // Covers a server that is shutting down.
                .exceptionally(failure -> Optional.empty());
    }

    private void remember(@NotNull Key key, @Nullable UUID owner) {
        long now = System.currentTimeMillis();
        if (this.answers.size() > PRUNE_ABOVE) {
            this.answers.values().removeIf(answer -> answer.until() <= now);
        }
        this.answers.put(key, new Answer(owner, now + REMEMBER_MILLIS));
    }
}
