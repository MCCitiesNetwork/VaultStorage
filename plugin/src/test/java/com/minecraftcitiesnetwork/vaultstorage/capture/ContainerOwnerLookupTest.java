package com.minecraftcitiesnetwork.vaultstorage.capture;

import com.minecraftcitiesnetwork.vaultstorage.Integrations;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Whose an unlocked container is: what the modules say, and how long the answer is kept. */
class ContainerOwnerLookupTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static Block blockAt(int x, int y, int z) {
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getUID")) {
                        return WORLD;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getWorld" -> world;
                    case "getX" -> x;
                    case "getY" -> y;
                    case "getZ" -> z;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A module that answers when the test says so. Counts how often it was asked. */
    private static final class Source implements ContainerOwnerSource {
        final List<CompletableFuture<Optional<UUID>>> asked = new ArrayList<>();
        final List<MarkedContainer> inChunk = new ArrayList<>();
        boolean throwsWhenAsked;

        @Override
        public @NotNull CompletableFuture<Optional<UUID>> ownerOf(@NotNull Block container) {
            if (this.throwsWhenAsked) {
                throw new IllegalStateException("broken");
            }
            CompletableFuture<Optional<UUID>> answer = new CompletableFuture<>();
            this.asked.add(answer);
            return answer;
        }

        @Override
        public @NotNull CompletableFuture<List<MarkedContainer>> ownedIn(@NotNull String world, int chunkX, int chunkZ) {
            if (this.throwsWhenAsked) {
                throw new IllegalStateException("broken");
            }
            return CompletableFuture.completedFuture(List.copyOf(this.inChunk));
        }

        void answer(UUID owner) {
            this.asked.get(this.asked.size() - 1).complete(Optional.ofNullable(owner));
        }
    }

    private final Integrations integrations = new Integrations(Logger.getAnonymousLogger());
    private final ContainerOwnerLookup lookup = new ContainerOwnerLookup(this.integrations::ownerSources, Runnable::run);
    private final Block chest = blockAt(10, 64, -3);

    @Test
    void withNoModuleAnUnlockedContainerIsNobodys() {
        CompletableFuture<Optional<UUID>> owner = this.lookup.ownerOf(this.chest);

        Assertions.assertTrue(owner.isDone());
        Assertions.assertEquals(Optional.empty(), owner.join());
        Assertions.assertNull(this.lookup.known(this.chest));
    }

    @Test
    void anAnswerIsKnownOnceItArrivesAndNotBefore() {
        Source source = new Source();
        this.integrations.addOwnerSource(source);

        CompletableFuture<Optional<UUID>> owner = this.lookup.ownerOf(this.chest);
        Assertions.assertFalse(owner.isDone());
        Assertions.assertNull(this.lookup.known(this.chest));

        source.answer(ALICE);

        Assertions.assertEquals(Optional.of(ALICE), owner.join());
        Assertions.assertEquals(ALICE, this.lookup.known(this.chest));
    }

    @Test
    void aRememberedAnswerIsNotAskedForAgain() {
        Source source = new Source();
        this.integrations.addOwnerSource(source);
        this.lookup.ownerOf(this.chest);
        source.answer(ALICE);

        CompletableFuture<Optional<UUID>> again = this.lookup.ownerOf(this.chest);

        Assertions.assertEquals(Optional.of(ALICE), again.join());
        Assertions.assertEquals(1, source.asked.size());
    }

    @Test
    void aForgottenContainerIsAskedAboutAgain() {
        Source source = new Source();
        this.integrations.addOwnerSource(source);
        this.lookup.ownerOf(this.chest);
        source.answer(ALICE);

        this.lookup.forget(this.chest);

        Assertions.assertNull(this.lookup.known(this.chest));
        Assertions.assertFalse(this.lookup.ownerOf(this.chest).isDone());
        Assertions.assertEquals(2, source.asked.size());
    }

    @Test
    void anotherContainerIsAskedAboutSeparately() {
        Source source = new Source();
        this.integrations.addOwnerSource(source);
        this.lookup.ownerOf(this.chest);
        source.answer(ALICE);

        Assertions.assertNull(this.lookup.known(blockAt(11, 64, -3)));
    }

    @Test
    void theFirstModuleThatNamesAPlayerDecides() {
        Source first = new Source();
        Source second = new Source();
        this.integrations.addOwnerSource(first);
        this.integrations.addOwnerSource(second);

        CompletableFuture<Optional<UUID>> owner = this.lookup.ownerOf(this.chest);
        second.answer(BOB);
        Assertions.assertFalse(owner.isDone());
        first.answer(ALICE);

        Assertions.assertEquals(Optional.of(ALICE), owner.join());
    }

    @Test
    void aModuleThatCannotSayLeavesItToTheNext() {
        Source first = new Source();
        Source second = new Source();
        this.integrations.addOwnerSource(first);
        this.integrations.addOwnerSource(second);

        CompletableFuture<Optional<UUID>> owner = this.lookup.ownerOf(this.chest);
        first.answer(null);
        second.answer(BOB);

        Assertions.assertEquals(Optional.of(BOB), owner.join());
    }

    @Test
    void aModuleThatFailsCountsAsNotKnowing() {
        Source broken = new Source();
        broken.throwsWhenAsked = true;
        Source working = new Source();
        this.integrations.addOwnerSource(broken);
        this.integrations.addOwnerSource(working);

        CompletableFuture<Optional<UUID>> owner = this.lookup.ownerOf(this.chest);
        working.answer(ALICE);

        Assertions.assertEquals(Optional.of(ALICE), owner.join());
    }

    @Test
    void aLookupThatFailsLaterCountsAsNotKnowing() {
        Source source = new Source();
        this.integrations.addOwnerSource(source);

        CompletableFuture<Optional<UUID>> owner = this.lookup.ownerOf(this.chest);
        source.asked.get(0).completeExceptionally(new IllegalStateException("database is down"));

        Assertions.assertEquals(Optional.empty(), owner.join());
    }

    @Test
    void aRemovedModuleIsNoLongerAsked() {
        Source source = new Source();
        this.integrations.addOwnerSource(source);
        this.integrations.removeOwnerSource(source);

        Assertions.assertEquals(Optional.empty(), this.lookup.ownerOf(this.chest).join());
        Assertions.assertEquals(0, source.asked.size());
    }

    @Test
    void theContainersInAChunkAreEveryModulesTogether() {
        Source first = new Source();
        first.inChunk.add(new MarkedContainer(1, 64, 1, ALICE));
        Source broken = new Source();
        broken.throwsWhenAsked = true;
        Source third = new Source();
        third.inChunk.add(new MarkedContainer(2, 64, 2, BOB));
        this.integrations.addOwnerSource(first);
        this.integrations.addOwnerSource(broken);
        this.integrations.addOwnerSource(third);

        List<MarkedContainer> marked = this.integrations.ownerSources().ownedIn("world", 0, 0).join();

        Assertions.assertEquals(List.of(new MarkedContainer(1, 64, 1, ALICE), new MarkedContainer(2, 64, 2, BOB)), marked);
    }

    @Test
    void aMarkedContainersChunkIsItsBlockPositionShiftedByFour() {
        MarkedContainer marked = new MarkedContainer(-1, 64, 33, ALICE);

        Assertions.assertEquals(-1, marked.chunkX());
        Assertions.assertEquals(2, marked.chunkZ());
    }
}
