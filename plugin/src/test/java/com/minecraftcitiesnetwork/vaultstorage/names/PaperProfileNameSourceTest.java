package com.minecraftcitiesnetwork.vaultstorage.names;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PaperProfileNameSourceTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void aFailedLookupIsEmptyNotAnException() {
        PaperProfileNameSource source = new PaperProfileNameSource(
                id -> CompletableFuture.failedFuture(new IllegalStateException("down")),
                name -> CompletableFuture.failedFuture(new IllegalStateException("down")));
        Assertions.assertEquals(Optional.empty(), source.nameOf(PLAYER).join());
        Assertions.assertEquals(Optional.empty(), source.uuidOf("Steve").join());
    }

    @Test
    void aNameNoAccountCanHaveIsEmpty() {
        PaperProfileNameSource source = new PaperProfileNameSource(
                id -> CompletableFuture.completedFuture(null),
                name -> {
                    throw new IllegalArgumentException(name);
                });
        Assertions.assertEquals(Optional.empty(), source.uuidOf("not a name!").join());
    }

    @Test
    void aProfileThatCouldNotBeCompletedIsEmpty() {
        PaperProfileNameSource source = new PaperProfileNameSource(
                id -> CompletableFuture.completedFuture(null),
                name -> CompletableFuture.completedFuture(null));
        Assertions.assertEquals(Optional.empty(), source.nameOf(PLAYER).join());
        Assertions.assertEquals(Optional.empty(), source.uuidOf("Steve").join());
    }
}
