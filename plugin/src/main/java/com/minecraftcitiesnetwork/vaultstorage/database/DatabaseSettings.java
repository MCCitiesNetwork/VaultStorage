package com.minecraftcitiesnetwork.vaultstorage.database;

import org.jetbrains.annotations.NotNull;

/** Where the database is. Read once at startup; not reloadable. */
public record DatabaseSettings(@NotNull String host, int port, @NotNull String database, @NotNull String user,
                               @NotNull String password, boolean useSsl) {

    /**
     * The connection URL. Timestamps are stored in UTC, and the three time zone options are what
     * make the driver convert them correctly on a JVM running in another zone.
     */
    public @NotNull String jdbcUrl() {
        return "jdbc:mariadb://" + this.host + ":" + this.port + "/" + this.database
                + "?sslMode=" + (this.useSsl ? "trust" : "disable")
                + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true";
    }
}
