package com.minecraftcitiesnetwork.vaultstorage.realty;

import com.minecraftcitiesnetwork.vaultstorage.VaultStoragePlugin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AutoVaultModuleTest {

    private static String resource(String name) throws IOException {
        try (InputStream stream = AutoVaultModuleTest.class.getClassLoader().getResourceAsStream(name)) {
            Assertions.assertNotNull(stream, name + " is not on the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void theManifestNamesTheEntryClassAndTheHost() throws IOException {
        String manifest = resource("module-manifest.yml");
        Assertions.assertTrue(manifest.contains("module-name: realty"));
        Assertions.assertTrue(manifest.contains(
                "entry-class: com.minecraftcitiesnetwork.vaultstorage.realty.AutoVaultModule"));
        Assertions.assertTrue(manifest.contains(
                "expected-plugin-class: " + VaultStoragePlugin.class.getName()));
        Assertions.assertTrue(manifest.contains("reloadable: true"));
    }

    /**
     * The loader resolves the entry class with Class.forName, which fails with a
     * NoClassDefFoundError if it names a Realty type on a server without Realty. The class file
     * is scanned as bytes because the entry class cannot be loaded here.
     */
    @Test
    void theEntryClassNamesNoRealtyType() throws IOException {
        String classFile = resource(
                "com/minecraftcitiesnetwork/vaultstorage/realty/AutoVaultModule.class");
        Assertions.assertFalse(classFile.contains("io/github/md5sha256/realty/"));
    }
}
