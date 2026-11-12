package com.minecraftcitiesnetwork.vaultstorage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PluginDescriptorTest {

    @Test
    void theDescriptorNamesTheMainClassAndHasItsVersionExpanded() throws IOException {
        String descriptor;
        try (InputStream stream = VaultStoragePlugin.class.getResourceAsStream("/paper-plugin.yml")) {
            Assertions.assertNotNull(stream, "paper-plugin.yml is not on the classpath");
            descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Assertions.assertTrue(descriptor.contains("main: " + VaultStoragePlugin.class.getName()));
        Assertions.assertFalse(descriptor.contains("${"), "the version placeholder was not expanded");
    }
}
