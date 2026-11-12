package com.minecraftcitiesnetwork.vaultstorage.autovault;

import com.minecraftcitiesnetwork.vaultstorage.database.VaultDatabase;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mariadb.jdbc.MariaDbDataSource;
import org.testcontainers.mariadb.MariaDBContainer;

/** Against the MariaDB production runs. Needs Docker. */
class PendingSweepStoreTest {

    private static final MariaDBContainer DATABASE = new MariaDBContainer("mariadb:10.11");
    private static final DataSource DATA_SOURCE;

    static {
        DATABASE.start();
        try {
            MariaDbDataSource source = new MariaDbDataSource(DATABASE.getJdbcUrl()
                    + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true");
            source.setUser(DATABASE.getUsername());
            source.setPassword(DATABASE.getPassword());
            DATA_SOURCE = source;
        } catch (SQLException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static final UUID WORLD = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OCCUPANT = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final Instant DUE = Instant.parse("2026-11-12T10:00:00.123Z");

    private PendingSweepStore store;

    private static PendingSweepStore newStore() throws SQLException {
        PendingSweepStore store = new PendingSweepStore(new VaultDatabase(DATA_SOURCE));
        store.migrate(Logger.getLogger("test"));
        return store;
    }

    @BeforeEach
    void startEmpty() throws SQLException {
        this.store = newStore();
        for (PendingSweep sweep : this.store.all()) {
            this.store.delete(sweep.worldUuid(), sweep.regionId());
        }
    }

    @Test
    void aStoredSweepIsReadBackToTheMillisecond() {
        PendingSweep sweep = new PendingSweep(WORLD, "plot1", OCCUPANT, DUE);
        this.store.save(sweep);
        Assertions.assertEquals(List.of(sweep), this.store.all());
    }

    @Test
    void savingTheSameRegionAgainReplacesItsSweep() {
        this.store.save(new PendingSweep(WORLD, "plot1", OCCUPANT, DUE));
        UUID newOccupant = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
        this.store.save(new PendingSweep(WORLD, "plot1", newOccupant, DUE.plusSeconds(60)));
        Assertions.assertEquals(List.of(new PendingSweep(WORLD, "plot1", newOccupant, DUE.plusSeconds(60))),
                this.store.all());
    }

    @Test
    void aFinishedSweepIsForgotten() {
        this.store.save(new PendingSweep(WORLD, "plot1", OCCUPANT, DUE));
        this.store.save(new PendingSweep(WORLD, "plot2", OCCUPANT, DUE));
        this.store.delete(WORLD, "plot1");
        Assertions.assertEquals(List.of("plot2"), this.store.all().stream().map(PendingSweep::regionId).toList());
    }

    @Test
    void aSweepSurvivesARestart() throws SQLException {
        this.store.save(new PendingSweep(WORLD, "plot1", OCCUPANT, DUE));
        // A new database object and a second migration, as after a restart.
        Assertions.assertEquals(1, newStore().all().size());
    }
}
