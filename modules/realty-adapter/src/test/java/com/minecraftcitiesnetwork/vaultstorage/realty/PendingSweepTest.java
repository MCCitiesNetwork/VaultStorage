package com.minecraftcitiesnetwork.vaultstorage.realty;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PendingSweepTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private static PendingSweep dueAt(Instant due) {
        return new PendingSweep(UUID.randomUUID(), "plot1", UUID.randomUUID(), due);
    }

    @Test
    void aSweepDueLaterWaitsTheRestOfItsDelay() {
        // 60 seconds is 1200 ticks.
        Assertions.assertEquals(1200, dueAt(NOW.plusSeconds(60)).ticksUntilDue(NOW));
        // A part of a tick rounds up, so a sweep never runs early.
        Assertions.assertEquals(2, dueAt(NOW.plusMillis(51)).ticksUntilDue(NOW));
    }

    @Test
    void aSweepThatCameDueWhileTheServerWasDownRunsSoonAfterStart() {
        Assertions.assertEquals(PendingSweep.OVERDUE_DELAY_TICKS, dueAt(NOW.minusSeconds(3600)).ticksUntilDue(NOW));
        Assertions.assertEquals(PendingSweep.OVERDUE_DELAY_TICKS, dueAt(NOW).ticksUntilDue(NOW));
    }

    @Test
    void theRegionIdIsStoredInLowerCaseAsWorldGuardKeepsIt() {
        Assertions.assertEquals("plot1", new PendingSweep(UUID.randomUUID(), "Plot1", UUID.randomUUID(), NOW).regionId());
    }
}
