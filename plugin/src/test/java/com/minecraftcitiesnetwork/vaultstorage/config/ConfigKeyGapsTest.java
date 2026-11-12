package com.minecraftcitiesnetwork.vaultstorage.config;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class ConfigKeyGapsTest {

    private static ConfigurationNode yaml(String text) throws ConfigurateException {
        return YamlConfigurationLoader.builder().source(() -> new BufferedReader(new StringReader(text))).build().load();
    }

    @Test
    void aKeyTheOperatorsFileLacksIsReportedByItsPath() throws ConfigurateException {
        ConfigurationNode bundled = yaml("scan:\n  batch-size: 20\n  cooldown-seconds: 20\nvault-list:\n  page-size: 20\n");
        ConfigurationNode operator = yaml("scan:\n  batch-size: 50\n");
        Assertions.assertEquals(List.of("scan.cooldown-seconds", "vault-list.page-size"),
                ConfigKeyGaps.missingKeys(bundled, operator));
    }

    @Test
    void aCompleteFileHasNoGapsAndExtraKeysAreNotReported() throws ConfigurateException {
        ConfigurationNode bundled = yaml("scan:\n  batch-size: 20\n");
        ConfigurationNode operator = yaml("scan:\n  batch-size: 50\n  something-else: 1\n");
        Assertions.assertEquals(List.of(), ConfigKeyGaps.missingKeys(bundled, operator));
    }
}
