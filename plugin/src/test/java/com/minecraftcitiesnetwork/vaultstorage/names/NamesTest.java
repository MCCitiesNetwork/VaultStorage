package com.minecraftcitiesnetwork.vaultstorage.names;

import com.minecraftcitiesnetwork.vaultstorage.Integrations;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
        Names names = new Names(paper, new Integrations());
        Assertions.assertEquals(Optional.of("Steve"), names.nameOf(PLAYER).join());
    }

    @Test
    void aPrimarySourceReplacesPaperAndTheBedrockSource() {
        FixedSource paper = new FixedSource(Optional.of("Steve"));
        FixedSource bedrock = new FixedSource(Optional.of(".Alex"));
        FixedSource namebook = new FixedSource(Optional.empty());
        Integrations integrations = new Integrations();
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
        Integrations integrations = new Integrations();
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
        Integrations integrations = new Integrations();
        integrations.primaryNames(new FixedSource(Optional.of("FromNamebook")));
        Names names = new Names(new FixedSource(Optional.of("Steve")), integrations);
        integrations.primaryNames(null);
        Assertions.assertEquals(Optional.of("Steve"), names.nameOf(PLAYER).join());
    }
}
