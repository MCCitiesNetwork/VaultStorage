package com.minecraftcitiesnetwork.vaultstorage.realty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;

class RealtyMessagesTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    @Test
    void theBundledFileHasTheSentenceTheModuleSends(@TempDir Path folder) throws IOException {
        ConfigurationNode messages = RealtyMessages.load(folder, LOGGER);

        Assertions.assertFalse(messages.node(RealtyMessages.OCCUPANT_NOTIFIED).virtual());
        Assertions.assertTrue(Files.exists(folder.resolve("messages.yml")));
    }

    @Test
    void anOperatorsWordingIsKept(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("messages.yml"), "occupant-notified: 'Cleared.'\n", StandardCharsets.UTF_8);

        ConfigurationNode messages = RealtyMessages.load(folder, LOGGER);

        Assertions.assertEquals("Cleared.", messages.node(RealtyMessages.OCCUPANT_NOTIFIED).getString());
    }

    @Test
    void aSentenceTheOperatorsFileLacksTakesTheBundledWording(@TempDir Path folder) throws IOException {
        Files.writeString(folder.resolve("messages.yml"), "something-else: 'x'\n", StandardCharsets.UTF_8);

        ConfigurationNode messages = RealtyMessages.load(folder, LOGGER);

        Assertions.assertFalse(messages.node(RealtyMessages.OCCUPANT_NOTIFIED).virtual());
    }
}
