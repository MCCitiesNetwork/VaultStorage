package com.minecraftcitiesnetwork.vaultstorage.config;

import com.minecraftcitiesnetwork.vaultstorage.database.DatabaseSettings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** The files in the jar are what an operator starts from and copies from, so they must parse. */
class BundledConfigTest {

    private static ConfigurationNode bundled(String name) throws IOException {
        InputStream stream = BundledConfigTest.class.getResourceAsStream("/" + name);
        Assertions.assertNotNull(stream, name + " is not in the jar");
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))).build().load();
    }

    @Test
    void theBundledSettingsParse() throws IOException {
        PluginSettings settings = bundled("settings.yml").get(PluginSettings.class);
        Assertions.assertNotNull(settings);
        Assertions.assertEquals(20, settings.scan().batchSize());
        Assertions.assertEquals(60, settings.scan().cacheTtlSeconds());
        Assertions.assertEquals(20, settings.scan().cooldownSeconds());
        Assertions.assertEquals(20, settings.vaultList().pageSize());
    }

    @Test
    void theBundledDatabaseSettingsParse() throws IOException {
        DatabaseSettings settings = bundled("database.yml").get(DatabaseSettings.class);
        Assertions.assertNotNull(settings);
        Assertions.assertEquals("mariadb://localhost:3306/vault_storage", settings.url());
        Assertions.assertTrue(settings.jdbcUrl().startsWith("jdbc:mariadb://localhost:3306/vault_storage?"));
    }

    @Test
    void aUrlThatAlreadyHasOptionsKeepsThem() {
        DatabaseSettings settings = new DatabaseSettings("mariadb://db:3306/v?sslMode=trust", "u", "p");
        Assertions.assertTrue(settings.jdbcUrl().startsWith("jdbc:mariadb://db:3306/v?sslMode=trust&connectionTimeZone=UTC"));
    }

    @Test
    void settingsLeftOutOrNonsensicalFallBackToTheirDefaults() {
        PluginSettings.Scan scan = new PluginSettings.Scan(0, -1, -1);
        Assertions.assertEquals(20, scan.batchSize());
        Assertions.assertEquals(0, scan.cacheTtlSeconds());
        Assertions.assertEquals(0, scan.cooldownSeconds());
        Assertions.assertEquals(20, new PluginSettings.VaultList(0).pageSize());
    }
}
