package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ClaimReleasesTest {

    private static final UUID VAULT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** A database that is down for a number of calls, then answers. */
    private static final class Database {
        int failuresLeft;
        final List<UUID> released = new ArrayList<>();

        void release(UUID vault) {
            if (this.failuresLeft > 0) {
                this.failuresLeft--;
                throw new IllegalStateException("database is down");
            }
            this.released.add(vault);
        }
    }

    private final Database database = new Database();
    private final Deque<Runnable> waiting = new ArrayDeque<>();
    private final ClaimReleases releases = new ClaimReleases(this.database::release, this.waiting::add,
            Logger.getAnonymousLogger());

    @Test
    void aReleaseThatWorksIsDoneAtOnceAndNotRepeated() {
        this.releases.release(VAULT);

        Assertions.assertEquals(List.of(VAULT), this.database.released);
        Assertions.assertTrue(this.waiting.isEmpty());
    }

    @Test
    void aReleaseThatFailsIsTriedAgainUntilTheDatabaseAnswers() {
        this.database.failuresLeft = 3;

        this.releases.release(VAULT);
        Assertions.assertEquals(List.of(), this.database.released);
        while (!this.waiting.isEmpty()) {
            this.waiting.poll().run();
        }

        Assertions.assertEquals(List.of(VAULT), this.database.released);
        Assertions.assertEquals(0, this.database.failuresLeft);
    }

    @Test
    void aFailedReleaseDoesNotThrowAtItsCaller() {
        this.database.failuresLeft = 1;

        Assertions.assertDoesNotThrow(() -> this.releases.release(VAULT));
        Assertions.assertEquals(1, this.waiting.size());
    }

    @Test
    void whenNoRetryCanBeScheduledTheReleaseGivesUpQuietly() {
        Database down = new Database();
        down.failuresLeft = 1;
        ClaimReleases stopping = new ClaimReleases(down::release, task -> {
            throw new java.util.concurrent.RejectedExecutionException("executor stopped");
        }, Logger.getAnonymousLogger());

        Assertions.assertDoesNotThrow(() -> stopping.release(VAULT));
        Assertions.assertEquals(List.of(), down.released);
    }
}
