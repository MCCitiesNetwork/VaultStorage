package com.minecraftcitiesnetwork.vaultstorage.realty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoVaultSettingsFileTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    @Test
    void theBundledFileParses(@TempDir Path folder) throws IOException {
        AutoVaultSettings settings = AutoVaultSettingsFile.load(folder.resolve("module"), folder, LOGGER);

        Assertions.assertTrue(settings.enabled());
        Assertions.assertEquals(60, settings.delaySeconds());
        Assertions.assertEquals(1200, settings.delayTicks());
        Assertions.assertTrue(settings.includeHangings());
        Assertions.assertTrue(settings.notifyOccupant());
        Assertions.assertTrue(Files.exists(folder.resolve("module/defaults/config.yml")));
    }

    @Test
    void oldValuesAreCarriedOverWhenTheFileIsCreated(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("config.yml"), """
                auto-vault:
                  enabled: false
                  delay-ticks: 30
                  notify-occupant: false
                """, StandardCharsets.UTF_8);

        AutoVaultSettings settings = AutoVaultSettingsFile.load(folder.resolve("module"), folder, LOGGER);

        Assertions.assertFalse(settings.enabled());
        Assertions.assertEquals(2, settings.delaySeconds());
        Assertions.assertTrue(settings.includeHangings());
        Assertions.assertFalse(settings.notifyOccupant());
        Assertions.assertTrue(Files.readString(folder.resolve("module/config.yml")).contains("# Grace period"));
    }

    @Test
    void anExistingFileIsNotRewritten(@TempDir Path folder) throws IOException {
        Path module = Files.createDirectories(folder.resolve("module"));
        String own = "enabled: true\ndelay-seconds: 5\ninclude-hangings: false\nnotify-occupant: true\n";
        Files.writeString(module.resolve("config.yml"), own, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("config.yml"), "auto-vault:\n  delay-ticks: 2400\n", StandardCharsets.UTF_8);

        AutoVaultSettings settings = AutoVaultSettingsFile.load(module, folder, LOGGER);

        Assertions.assertEquals(5, settings.delaySeconds());
        Assertions.assertEquals(own, Files.readString(module.resolve("config.yml")));
    }
}
