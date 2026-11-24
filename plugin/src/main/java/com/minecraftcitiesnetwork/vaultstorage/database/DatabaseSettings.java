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
     *
     * <p>The two timeouts apply unless the url sets its own. The driver's default is to wait
     * for an answer for ever, so a connection that died in the middle of a query would hold a
     * pool thread until the server stops.
     */
    public @NotNull String jdbcUrl() {
        StringBuilder jdbc = new StringBuilder("jdbc:").append(this.url)
                .append(this.url.contains("?") ? "&" : "?")
                .append("connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&preserveInstants=true");
        // In milliseconds. A minute for an answer: a migration of a large table is one statement.
        appendUnlessSet(jdbc, "connectTimeout", 10_000);
        appendUnlessSet(jdbc, "socketTimeout", 60_000);
        return jdbc.toString();
    }

    private void appendUnlessSet(@NotNull StringBuilder jdbc, @NotNull String option, int value) {
        if (!this.url.contains(option + "=")) {
            jdbc.append('&').append(option).append('=').append(value);
        }
    }
}
