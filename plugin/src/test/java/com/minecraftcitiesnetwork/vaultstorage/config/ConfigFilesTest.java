package com.minecraftcitiesnetwork.vaultstorage.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;

class ConfigFilesTest {

    private static final String BUNDLED = "# a comment\ngreeting: 'hello'\nfarewell: 'bye'\n";

    @Test
    void anAbsentFileIsCreatedFromTheBundledDefault(@TempDir Path folder) throws IOException {
        ConfigurationNode node = files(folder).load("messages.yml", BUNDLED, UnaryOperator.identity());

        Assertions.assertEquals("hello", node.node("greeting").getString());
        Assertions.assertEquals(BUNDLED, Files.readString(folder.resolve("messages.yml")));
        Assertions.assertEquals(BUNDLED, Files.readString(folder.resolve("defaults/messages.yml")));
    }

    @Test
    void theOperatorsWordingWinsAndAKeyTheyLackIsFilledInMemory(@TempDir Path folder) throws IOException {
        String own = "greeting: 'good day'\n";
        Files.writeString(folder.resolve("messages.yml"), own, StandardCharsets.UTF_8);

        ConfigurationNode node = files(folder).loadFilled("messages.yml", BUNDLED);

        Assertions.assertEquals("good day", node.node("greeting").getString());
        Assertions.assertEquals("bye", node.node("farewell").getString());
        Assertions.assertEquals(own, Files.readString(folder.resolve("messages.yml")));
    }

    @Test
    void aFileThatDoesNotParseFailsAndIsLeftAsItIs(@TempDir Path folder) throws IOException {
        String broken = "greeting: 'unterminated\n  farewell: [\n";
        Files.writeString(folder.resolve("messages.yml"), broken, StandardCharsets.UTF_8);

        Assertions.assertThrows(IOException.class, () -> files(folder).loadFilled("messages.yml", BUNDLED));
        Assertions.assertEquals(broken, Files.readString(folder.resolve("messages.yml")));
    }

    @Test
    void theOldConfigIsAnEmptyNodeWhenThereIsNone(@TempDir Path folder) {
        Assertions.assertTrue(files(folder).legacy().node("mysql", "host").virtual());
    }

    private static ConfigFiles files(Path folder) {
        return new ConfigFiles(folder, Logger.getLogger("test"));
    }
}
