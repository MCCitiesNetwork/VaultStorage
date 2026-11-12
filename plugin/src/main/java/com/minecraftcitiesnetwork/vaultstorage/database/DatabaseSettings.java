package com.minecraftcitiesnetwork.vaultstorage.database;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Required;
import org.spongepowered.configurate.objectmapping.meta.Setting;

/**
 * Where the database is, from {@code database.yml}. Read once at startup; not reloadable.
 *
 * @param url the address without {@code jdbc:} in front, such as {@code mariadb://host:3306/name}
 */
@ConfigSerializable
public record DatabaseSettings(
        @Setting("url") @Required @NotNull String url,
        @Setting("username") @Required @NotNull String username,
        @Setting("password") @Required @NotNull String password
) {

    /**
     * The JDBC URL. Timestamps are stored in UTC, and the three time zone options are what make
     * the driver convert them correctly on a JVM running in another zone.
     */
    public @NotNull String jdbcUrl() {
        return "jdbc:" + this.url + (this.url.contains("?") ? "&" : "?")
                + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true";
    }
}
