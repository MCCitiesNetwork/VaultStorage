package com.minecraftcitiesnetwork.vaultstorage.config;

import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LegacyConfigImportTest {

    private static final String BUNDLED = """
            # Where the vaults are kept.
            url: "mariadb://localhost:3306/vault_storage"
            username: "root"
            # Leave empty for none.
            password: ""
            scan:
              # Blocks per tick.
              batch-size: 20
              cooldown-seconds: 20
            """;

    @Test
    void theOldHostPortAndDatabaseBecomeOneUrl() {
        Assertions.assertEquals("mariadb://db.example:3307/vaults",
                LegacyConfigImport.databaseUrl("db.example", 3307, "vaults", false));
        Assertions.assertEquals("mariadb://db.example:3307/vaults?sslMode=trust",
                LegacyConfigImport.databaseUrl("db.example", 3307, "vaults", true));
    }

    @Test
    void valuesAreFilledInAndTheCommentsAreKept() {
        String filled = LegacyConfigImport.fill(BUNDLED, Map.of(
                "url", "mariadb://db.example:3307/vaults",
                "username", "vault",
                "scan.batch-size", "50"));
        Assertions.assertEquals("""
                # Where the vaults are kept.
                url: "mariadb://db.example:3307/vaults"
                username: "vault"
                # Leave empty for none.
                password: ""
                scan:
                  # Blocks per tick.
                  batch-size: 50
                  cooldown-seconds: 20
                """, filled);
    }

    @Test
    void aPasswordWithQuotesAndBackslashesSurvives() {
        String filled = LegacyConfigImport.fill(BUNDLED, Map.of("password", "pa\"ss\\word: #1"));
        Assertions.assertTrue(filled.contains("password: \"pa\\\"ss\\\\word: #1\"\n"));
    }

    @Test
    void withNothingToImportTheBundledDefaultIsUnchanged() {
        Assertions.assertEquals(BUNDLED, LegacyConfigImport.fill(BUNDLED, Map.of()));
    }

    @Test
    void ticksBecomeWholeSecondsRoundedUp() {
        Assertions.assertEquals(60, LegacyConfigImport.ticksToSeconds(1200));
        Assertions.assertEquals(1, LegacyConfigImport.ticksToSeconds(1));
        Assertions.assertEquals(0, LegacyConfigImport.ticksToSeconds(0));
        Assertions.assertEquals(0, LegacyConfigImport.ticksToSeconds(-5));
    }
}
