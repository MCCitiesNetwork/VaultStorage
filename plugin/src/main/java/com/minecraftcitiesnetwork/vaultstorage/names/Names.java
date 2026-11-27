package com.minecraftcitiesnetwork.vaultstorage.names;

import com.minecraftcitiesnetwork.vaultstorage.Integrations;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The name lookup host code calls. A module's primary source answers alone when one is loaded;
 * otherwise Paper answers, then a module's Bedrock source if Paper has nothing.
 *
 * <p>The slots are read on every call, because modules start after the host's services exist
 * and may stop while it runs.
 *
 * <p>A name is remembered for a while, and so is the fact that nobody knows one, so a menu or
 * an action bar that shows the same owner again does not ask again. A lookup already under way
 * is shared.
 */
public final class Names implements NameSource {

    private static final long KNOWN_MILLIS = 10 * 60_000L;
    /** Shorter: a player who has never joined may join. */
    private static final long UNKNOWN_MILLIS = 60_000L;
    private static final int PRUNE_ABOVE = 2_000;

    private record Remembered(@Nullable String name, long until) {
    }

    private final @NotNull NameSource paper;
    private final @NotNull Integrations integrations;
    private final @NotNull LongSupplier clock;
    private final Map<UUID, Remembered> remembered = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Optional<String>>> asking = new ConcurrentHashMap<>();

    public Names(@NotNull NameSource paper, @NotNull Integrations integrations) {
        this(paper, integrations, System::currentTimeMillis);
    }

    Names(@NotNull NameSource paper, @NotNull Integrations integrations, @NotNull LongSupplier clock) {
        this.paper = paper;
        this.integrations = integrations;
        this.clock = clock;
    }

    /** What stands in for a name nobody knows: the start of the id. */
    public static @NotNull String shortId(@NotNull UUID player) {
        return player.toString().substring(0, 8);
    }

    @Override
    public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
        Remembered known = this.remembered.get(player);
        if (known != null && known.until() > this.clock.getAsLong()) {
            return CompletableFuture.completedFuture(Optional.ofNullable(known.name()));
        }
        CompletableFuture<Optional<String>> underWay = this.asking.get(player);
        if (underWay != null) {
            return underWay;
        }
        CompletableFuture<Optional<String>> asked = current().nameOf(player)
                .thenApply(name -> name.filter(found -> !found.isBlank()));
        if (!asked.isDone()) {
            this.asking.put(player, asked);
        }
        return asked.whenComplete((name, failure) -> {
            this.asking.remove(player);
            if (name != null) {
                remember(player, name.orElse(null));
            }
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
        return current().uuidOf(name);
    }

    /** The player's name, or the start of their id when nobody knows one. */
    public @NotNull CompletableFuture<String> displayName(@NotNull UUID player) {
        return nameOf(player).thenApply(name -> name.orElse(shortId(player)));
    }

    /** {@link #displayName} for each of these players, once they are all known. */
    public @NotNull CompletableFuture<Map<UUID, String>> displayNames(@NotNull Collection<UUID> players) {
        Set<UUID> distinct = new LinkedHashSet<>(players);
        Map<UUID, CompletableFuture<String>> lookups = new LinkedHashMap<>();
        distinct.forEach(player -> lookups.put(player, displayName(player)));
        return CompletableFuture.allOf(lookups.values().toArray(CompletableFuture[]::new)).thenApply(done -> {
            Map<UUID, String> names = new LinkedHashMap<>();
            lookups.forEach((player, name) -> names.put(player, name.join()));
            return names;
        });
    }

    /**
     * {@link #displayName} without waiting: the name if it is remembered, and otherwise the
     * start of the id, with a lookup started so the name is there next time. For text that is
     * drawn again and again, such as an action bar.
     */
    public @NotNull String displayNameNow(@NotNull UUID player) {
        Remembered known = this.remembered.get(player);
        if (known != null && known.until() > this.clock.getAsLong()) {
            return known.name() == null ? shortId(player) : known.name();
        }
        nameOf(player);
        return shortId(player);
    }

    private void remember(@NotNull UUID player, @Nullable String name) {
        long now = this.clock.getAsLong();
        if (this.remembered.size() > PRUNE_ABOVE) {
            this.remembered.values().removeIf(entry -> entry.until() <= now);
        }
        this.remembered.put(player, new Remembered(name, now + (name == null ? UNKNOWN_MILLIS : KNOWN_MILLIS)));
    }

    private @NotNull NameSource current() {
        NameSource primary = this.integrations.primaryNames();
        if (primary != null) {
            return primary;
        }
        NameSource bedrock = this.integrations.bedrockNames();
        return bedrock == null ? this.paper : new FallbackNameSource(this.paper, bedrock);
    }
}
