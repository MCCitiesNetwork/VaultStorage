package com.minecraftcitiesnetwork.vaultstorage.names;

import java.util.logging.Logger;
import com.minecraftcitiesnetwork.vaultstorage.Integrations;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NamesTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** Answers one fixed name, or empty, and counts how often it was asked. */
    private static final class FixedSource implements NameSource {
        private final Optional<String> name;
        private final AtomicInteger asked = new AtomicInteger();

        private FixedSource(@NotNull Optional<String> name) {
            this.name = name;
        }

        @Override
        public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
            this.asked.incrementAndGet();
            return CompletableFuture.completedFuture(this.name);
        }

        @Override
        public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
            this.asked.incrementAndGet();
            return CompletableFuture.completedFuture(this.name.map(ignored -> PLAYER));
        }
    }

    @Test
    void withNoModulesPaperAnswers() {
        FixedSource paper = new FixedSource(Optional.of("Steve"));
        Names names = new Names(paper, new Integrations(Logger.getLogger("test")));
        Assertions.assertEquals(Optional.of("Steve"), names.nameOf(PLAYER).join());
    }

    @Test
    void aPrimarySourceReplacesPaperAndTheBedrockSource() {
        FixedSource paper = new FixedSource(Optional.of("Steve"));
        FixedSource bedrock = new FixedSource(Optional.of(".Alex"));
        FixedSource namebook = new FixedSource(Optional.empty());
        Integrations integrations = new Integrations(Logger.getLogger("test"));
        integrations.primaryNames(namebook);
        integrations.bedrockNames(bedrock);
        Names names = new Names(paper, integrations);
        Assertions.assertEquals(Optional.empty(), names.nameOf(PLAYER).join());
        Assertions.assertEquals(0, paper.asked.get());
        Assertions.assertEquals(0, bedrock.asked.get());
    }

    @Test
    void theBedrockSourceIsAskedOnlyWhenPaperHasNoAnswer() {
        FixedSource bedrock = new FixedSource(Optional.of(".Alex"));
        Integrations integrations = new Integrations(Logger.getLogger("test"));
        integrations.bedrockNames(bedrock);

        Names found = new Names(new FixedSource(Optional.of("Steve")), integrations);
        Assertions.assertEquals(Optional.of("Steve"), found.nameOf(PLAYER).join());
        Assertions.assertEquals(0, bedrock.asked.get());

        Names missed = new Names(new FixedSource(Optional.empty()), integrations);
        Assertions.assertEquals(Optional.of(".Alex"), missed.nameOf(PLAYER).join());
        Assertions.assertEquals(Optional.of(PLAYER), missed.uuidOf(".Alex").join());
    }

    @Test
    void aModuleThatUnloadsHandsNamesBackToPaper() {
        Integrations integrations = new Integrations(Logger.getLogger("test"));
        integrations.primaryNames(new FixedSource(Optional.of("FromNamebook")));
        Names names = new Names(new FixedSource(Optional.of("Steve")), integrations);
        integrations.primaryNames(null);
        Assertions.assertEquals(Optional.of("Steve"), names.nameOf(PLAYER).join());
    }

    @Test
    void aNameIsRememberedAndNotAskedForAgain() {
        FixedSource paper = new FixedSource(Optional.of("Steve"));
        Names names = new Names(paper, new Integrations(Logger.getLogger("test")));

        names.nameOf(PLAYER).join();

        Assertions.assertEquals(Optional.of("Steve"), names.nameOf(PLAYER).join());
        Assertions.assertEquals(1, paper.asked.get());
    }

    @Test
    void aNameNobodyKnowsIsAskedForAgainAfterAMinute() {
        FixedSource paper = new FixedSource(Optional.empty());
        AtomicLong now = new AtomicLong();
        Names names = new Names(paper, new Integrations(Logger.getLogger("test")), now::get);

        names.nameOf(PLAYER).join();
        now.set(59_000);
        names.nameOf(PLAYER).join();
        Assertions.assertEquals(1, paper.asked.get());

        now.set(61_000);
        names.nameOf(PLAYER).join();
        Assertions.assertEquals(2, paper.asked.get());
    }

    @Test
    void aNameNobodyKnowsIsShownAsTheStartOfTheId() {
        Names names = new Names(new FixedSource(Optional.empty()), new Integrations(Logger.getLogger("test")));

        Assertions.assertEquals("11111111", names.displayName(PLAYER).join());
        Assertions.assertEquals(Map.of(PLAYER, "11111111"), names.displayNames(List.of(PLAYER, PLAYER)).join());
    }

    @Test
    void aNameShownWithoutWaitingIsTheIdUntilTheLookupIsIn() {
        CompletableFuture<Optional<String>> answer = new CompletableFuture<>();
        NameSource slow = new NameSource() {
            @Override
            public @NotNull CompletableFuture<Optional<String>> nameOf(@NotNull UUID player) {
                return answer;
            }

            @Override
            public @NotNull CompletableFuture<Optional<UUID>> uuidOf(@NotNull String name) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
        Names names = new Names(slow, new Integrations(Logger.getLogger("test")));

        Assertions.assertEquals("11111111", names.displayNameNow(PLAYER));
        answer.complete(Optional.of("Steve"));

        Assertions.assertEquals("Steve", names.displayNameNow(PLAYER));
    }
}
