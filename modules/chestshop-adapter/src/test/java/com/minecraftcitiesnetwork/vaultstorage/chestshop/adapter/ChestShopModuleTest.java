package com.minecraftcitiesnetwork.vaultstorage.chestshop.adapter;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChestShopModuleTest {

    private static String resource(String name) throws IOException {
        try (InputStream stream = ChestShopModuleTest.class.getClassLoader().getResourceAsStream(name)) {
            Assertions.assertNotNull(stream, name + " is not on the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void theManifestNamesTheEntryClassAndTheHost() throws IOException {
        String manifest = resource("module-manifest.yml");
        Assertions.assertTrue(manifest.contains("module-name: chestshop-adapter"));
        Assertions.assertTrue(manifest.contains(
                "entry-class: com.minecraftcitiesnetwork.vaultstorage.chestshop.adapter.ChestShopModule"));
        Assertions.assertTrue(manifest.contains(
                "expected-plugin-class: " + VaultStoragePlugin.class.getName()));
        Assertions.assertTrue(manifest.contains("reloadable: false"));
    }

    /**
     * The loader resolves the entry class with Class.forName, which fails with a
     * NoClassDefFoundError if it names a ChestShop type on a server without ChestShop. The class
     * file is scanned as bytes because the entry class cannot be loaded here.
     */
    @Test
    void theEntryClassNamesNoChestShopType() throws IOException {
        String classFile = resource(
                "com/minecraftcitiesnetwork/vaultstorage/chestshop/adapter/ChestShopModule.class");
        Assertions.assertFalse(classFile.contains("com/Acrobot/"));
    }
}
