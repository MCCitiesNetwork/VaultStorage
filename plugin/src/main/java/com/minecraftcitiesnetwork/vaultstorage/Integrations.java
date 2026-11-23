package com.minecraftcitiesnetwork.vaultstorage;

import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.logging.Level;
import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerAttachments;
import com.minecraftcitiesnetwork.vaultstorage.capture.ContainerOwnerSource;
import com.minecraftcitiesnetwork.vaultstorage.capture.MarkedContainer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import com.minecraftcitiesnetwork.vaultstorage.names.NameSource;
import com.minecraftcitiesnetwork.vaultstorage.placement.PlacementGrant;
import com.minecraftcitiesnetwork.vaultstorage.vault.OwnerNotifier;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Location;
import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What feature modules supply to the host. Each slot has a built-in default; a module fills its
 * slot when it starts and passes {@code null} to restore the default when it stops.
 *
 * <p>Host code reads a slot each time it uses it, never once at construction. The fields are
 * volatile because name lookups complete on other threads.
 */
public final class Integrations {

    private volatile @Nullable NameSource primaryNames;
    private volatile @Nullable NameSource bedrockNames;
    private volatile @Nullable Predicate<UUID> bedrockClient;
    private volatile @Nullable OwnerNotifier ownerNotifier;
    private final List<PlacementGrant> placementGrants = new CopyOnWriteArrayList<>();
    private final List<ContainerAttachments> attachments = new CopyOnWriteArrayList<>();
    private final ContainerAttachments everyAttachment = new EveryAttachment();
    private final List<ContainerOwnerSource> ownerSources = new CopyOnWriteArrayList<>();
    private final ContainerOwnerSource everyOwnerSource = new EveryOwnerSource();
    private final @NotNull Logger logger;

    public Integrations(@NotNull Logger logger) {
        this.logger = logger;
    }

    public void primaryNames(@Nullable NameSource source) {
        this.primaryNames = source;
    }

    public @Nullable NameSource primaryNames() {
        return this.primaryNames;
    }

    public void bedrockNames(@Nullable NameSource source) {
        this.bedrockNames = source;
    }

    public @Nullable NameSource bedrockNames() {
        return this.bedrockNames;
    }

    public void bedrockClient(@Nullable Predicate<UUID> check) {
        this.bedrockClient = check;
    }

    /** Whether the player is on a Bedrock client. False when no module can tell. */
    public boolean isBedrockClient(@NotNull UUID player) {
        Predicate<UUID> check = this.bedrockClient;
        return check != null && check.test(player);
    }

    public void ownerNotifier(@Nullable OwnerNotifier notifier) {
        this.ownerNotifier = notifier;
    }

    /** How a vault's owner is told about it; null when no notification module is loaded. */
    public @Nullable OwnerNotifier ownerNotifier() {
        return this.ownerNotifier;
    }

    public void addPlacementGrant(@NotNull PlacementGrant grant) {
        this.placementGrants.add(grant);
    }

    public void removePlacementGrant(@NotNull PlacementGrant grant) {
        this.placementGrants.remove(grant);
    }

    /**
     * Whether any grant lets the player place a vault at the target. A grant that fails is a
     * no: a broken integration must not open placement to everyone.
     */
    public boolean mayPlace(@NotNull UUID player, @NotNull Location target) {
        for (PlacementGrant grant : this.placementGrants) {
            try {
                if (grant.mayPlace(player, target)) {
                    return true;
                }
            } catch (RuntimeException ex) {
                // Counts as "this grant does not apply": a broken module must not open land up.
                this.logger.log(Level.WARNING, "A placement grant failed and was counted as a refusal: "
                        + grant.getClass().getName(), ex);
            }
        }
        return false;
    }

    public void addAttachments(@NotNull ContainerAttachments provider) {
        this.attachments.add(provider);
    }

    public void removeAttachments(@NotNull ContainerAttachments provider) {
        this.attachments.remove(provider);
    }

    /** What every module knows to be attached to containers. With no module, nothing is. */
    public @NotNull ContainerAttachments attachments() {
        return this.everyAttachment;
    }

    public void addOwnerSource(@NotNull ContainerOwnerSource source) {
        this.ownerSources.add(source);
    }

    public void removeOwnerSource(@NotNull ContainerOwnerSource source) {
        this.ownerSources.remove(source);
    }

    /** Every module that can say whose an unlocked container is. With none, it is nobody's. */
    public @NotNull ContainerOwnerSource ownerSources() {
        return this.everyOwnerSource;
    }

    private void failed(@NotNull Object provider, @NotNull Throwable failure) {
        this.logger.log(Level.WARNING, "A module's lookup failed and was counted as knowing nothing: "
                + provider.getClass().getName(), failure);
    }

    /** Asks each module in turn. One that fails is logged and counts as knowing nothing. */
    private final class EveryAttachment implements ContainerAttachments {

        @Override
        public boolean isAttachment(@Nullable Block block) {
            for (ContainerAttachments provider : Integrations.this.attachments) {
                try {
                    if (provider.isAttachment(block)) {
                        return true;
                    }
                } catch (RuntimeException ex) {
                    failed(provider, ex);
                }
            }
            return false;
        }

        @Override
        public @NotNull List<Block> attachedTo(@Nullable Block container) {
            List<Block> found = new ArrayList<>();
            for (ContainerAttachments provider : Integrations.this.attachments) {
                try {
                    found.addAll(provider.attachedTo(container));
                } catch (RuntimeException ex) {
                    failed(provider, ex);
                }
            }
            return found;
        }

        @Override
        public boolean remove(@Nullable Player actor, @Nullable Block attachment) {
            for (ContainerAttachments provider : Integrations.this.attachments) {
                try {
                    if (provider.isAttachment(attachment)) {
                        return provider.remove(actor, attachment);
                    }
                } catch (RuntimeException ex) {
                    failed(provider, ex);
                }
            }
            return false;
        }
    }

    /** Asks every module. One that fails is logged and counts as knowing nothing. */
    private final class EveryOwnerSource implements ContainerOwnerSource {

        /** The first source, in the order they were added, that names a player. */
        @Override
        public @NotNull CompletableFuture<Optional<UUID>> ownerOf(@NotNull Block container) {
            // All are asked now, on the calling thread: a source may read the world to start with.
            List<CompletableFuture<Optional<UUID>>> answers = new ArrayList<>();
            for (ContainerOwnerSource source : Integrations.this.ownerSources) {
                answers.add(guarded(source, () -> source.ownerOf(container), Optional.empty()));
            }
            return CompletableFuture.allOf(answers.toArray(CompletableFuture[]::new))
                    .thenApply(done -> answers.stream().map(CompletableFuture::join)
                            .filter(Optional::isPresent).findFirst().orElse(Optional.empty()));
        }

        @Override
        public @NotNull CompletableFuture<List<MarkedContainer>> ownedIn(@NotNull String world, int chunkX, int chunkZ) {
            List<CompletableFuture<List<MarkedContainer>>> answers = new ArrayList<>();
            for (ContainerOwnerSource source : Integrations.this.ownerSources) {
                answers.add(guarded(source, () -> source.ownedIn(world, chunkX, chunkZ), List.of()));
            }
            return CompletableFuture.allOf(answers.toArray(CompletableFuture[]::new))
                    .thenApply(done -> answers.stream().flatMap(answer -> answer.join().stream()).toList());
        }

        private <T> @NotNull CompletableFuture<T> guarded(@NotNull ContainerOwnerSource source,
                                                         @NotNull Supplier<CompletableFuture<T>> call,
                                                         @NotNull T nothing) {
            try {
                return call.get().exceptionally(failure -> {
                    failed(source, failure);
                    return nothing;
                });
            } catch (RuntimeException ex) {
                failed(source, ex);
                return CompletableFuture.completedFuture(nothing);
            }
        }
    }
}
