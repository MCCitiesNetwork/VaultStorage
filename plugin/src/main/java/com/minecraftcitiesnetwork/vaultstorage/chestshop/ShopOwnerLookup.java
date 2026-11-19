package com.minecraftcitiesnetwork.vaultstorage.chestshop;

import com.minecraftcitiesnetwork.vaultstorage.Integrations;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Whose a shop container is, for a container nobody has locked. Asks the shop registry, which
 * is a database, so the answer comes later and is remembered for a short while: the capture
 * policy and the action bar run on the main thread and can only use what is already known.
 *
 * <p>Main thread only.
 */
public final class ShopOwnerLookup {

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

    private final @NotNull Integrations integrations;
    private final @NotNull Executor mainThread;
    private final Map<Key, Answer> answers = new HashMap<>();

    public ShopOwnerLookup(@NotNull Integrations integrations, @NotNull Executor mainThread) {
        this.integrations = integrations;
        this.mainThread = mainThread;
    }

    /** The player owner found by an earlier {@link #ownerOf}, or null when none is known. */
    public @Nullable UUID known(@NotNull Block container) {
        Answer answer = this.answers.get(Key.of(container));
        return answer != null && answer.until() > System.currentTimeMillis() ? answer.owner() : null;
    }

    /**
     * The player every shop sign on the container belongs to. Already complete when there is
     * nothing to ask: no shop plugin, no registry, no shop sign, or a recent answer. Otherwise
     * it completes on the main thread. Never completes exceptionally.
     */
    public @NotNull CompletableFuture<Optional<UUID>> ownerOf(@NotNull Block container) {
        ShopDirectory directory = this.integrations.shopDirectory();
        ChestShopService shops = this.integrations.chestShop();
        if (directory == null || !shops.isAvailable()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        List<ShopPosition> signs = shops.findShopSigns(container).stream()
                .map(sign -> new ShopPosition(sign.getX(), sign.getY(), sign.getZ())).toList();
        if (signs.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Key key = Key.of(container);
        Answer remembered = this.answers.get(key);
        long now = System.currentTimeMillis();
        if (remembered != null && remembered.until() > now) {
            return CompletableFuture.completedFuture(Optional.ofNullable(remembered.owner()));
        }

        String world = container.getWorld().getName();
        Set<List<Integer>> chunks = new LinkedHashSet<>();
        signs.forEach(sign -> chunks.add(List.of(sign.chunkX(), sign.chunkZ())));
        List<CompletableFuture<List<Shop>>> reads = new ArrayList<>();
        chunks.forEach(chunk -> reads.add(directory.shopsInChunk(world, chunk.get(0), chunk.get(1))));
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new))
                .thenApplyAsync(done -> {
                    List<Shop> registry = new ArrayList<>();
                    reads.forEach(read -> registry.addAll(read.join()));
                    Optional<UUID> owner = ShopOwners.playerOwner(registry, signs);
                    remember(key, owner.orElse(null));
                    return owner;
                }, this.mainThread)
                // The directory does not fail; this covers a server that is shutting down.
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
