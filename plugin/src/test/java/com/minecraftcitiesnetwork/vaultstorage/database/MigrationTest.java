package com.minecraftcitiesnetwork.vaultstorage.database;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MigrationTest extends DatabaseTestBase {

    private static final String VAULT_ONE = "bbbbbbbb-0000-0000-0000-000000000001";

    @BeforeEach
    void startEmpty() throws SQLException {
        dropEverything();
    }

    private static boolean tableExists(String name) throws SQLException {
        return queryLong("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() "
                + "AND BINARY table_name = '" + name + "'") == 1;
    }

    private static void loadLegacyDatabase() throws SQLException, IOException {
        runScript("sql/migrations/V1__legacy_baseline.sql");
        runScript("legacy-rows.sql");
    }

    @Test
    void anEmptyDatabaseMigratesToTheLatestVersion() throws SQLException {
        migrate();
        Assertions.assertEquals(4, queryLong("SELECT MAX(version) FROM vaultstorage_schema_version"));
        for (String table : new String[] {"Vault", "VaultItem", "World"}) {
            Assertions.assertTrue(tableExists(table), table + " is missing");
        }
        for (String table : new String[] {"vaults", "vault_items", "vault_owners", "worlds"}) {
            Assertions.assertFalse(tableExists(table), table + " is still there");
        }
    }

    @Test
    void migratingTwiceChangesNothing() throws SQLException {
        migrate();
        migrate();
        Assertions.assertEquals(4, queryLong("SELECT MAX(version) FROM vaultstorage_schema_version"));
        Assertions.assertEquals(4, queryLong("SELECT COUNT(*) FROM vaultstorage_schema_version"));
    }

    @Test
    void aLegacyDatabaseKeepsItsRows() throws SQLException, IOException {
        loadLegacyDatabase();
        migrate();

        Assertions.assertEquals(3, queryLong("SELECT COUNT(*) FROM Vault"));
        Assertions.assertEquals(5, queryLong("SELECT COUNT(*) FROM VaultItem"));
        Assertions.assertEquals(2, queryLong("SELECT COUNT(*) FROM World"));

        try (Connection connection = dataSource().getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery(
                    "SELECT ownerUuid, worldUuid, x, y, z, material, blockData, createdTime, updatedTime, "
                            + "DATE_FORMAT(createdTime, '%Y-%m-%d %H:%i:%s.%f') AS storedCreated "
                            + "FROM Vault WHERE uuid = '" + VAULT_ONE + "'")) {
                Assertions.assertTrue(row.next());
                Assertions.assertEquals("cccccccc-0000-0000-0000-000000000001", row.getString("ownerUuid"));
                Assertions.assertEquals("aaaaaaaa-0000-0000-0000-000000000001", row.getString("worldUuid"));
                Assertions.assertEquals(10, row.getInt("x"));
                Assertions.assertEquals(64, row.getInt("y"));
                Assertions.assertEquals(-20, row.getInt("z"));
                Assertions.assertEquals("CHEST", row.getString("material"));
                Assertions.assertEquals("minecraft:chest[facing=north,type=single,waterlogged=false]",
                        row.getString("blockData"));
                // What the database holds, independent of how a driver converts it.
                Assertions.assertEquals("2024-11-09 06:38:40.396000", row.getString("storedCreated"));
                Instant expected = Instant.ofEpochMilli(1731134320396L);
                Assertions.assertEquals(expected, row.getTimestamp("createdTime").toInstant());
                Assertions.assertEquals(expected, row.getTimestamp("updatedTime").toInstant());
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT item, amount FROM VaultItem WHERE vaultUuid = '" + VAULT_ONE + "' AND slot = 1")) {
                Assertions.assertTrue(row.next());
                byte[] item = row.getBytes("item");
                Assertions.assertEquals(60000, item.length);
                Assertions.assertEquals('<', item[0]);
                Assertions.assertEquals('>', item[item.length - 1]);
                Assertions.assertEquals(1, row.getInt("amount"));
            }
        }
        Assertions.assertEquals(2, queryLong(
                "SELECT COUNT(*) FROM information_schema.key_column_usage WHERE table_schema = DATABASE() "
                        + "AND BINARY table_name = 'VaultItem' AND constraint_name = 'PRIMARY'"));
    }

    @Test
    void theStructureAndFinaliseStepsCanRunAgain() throws SQLException, IOException {
        loadLegacyDatabase();
        migrate();
        // V3 is not repeated: its source columns are gone, which is what the version table guards.
        runScript("sql/migrations/V2__playbook_structure.sql");
        runScript("sql/migrations/V4__playbook_finalise.sql");
        Assertions.assertEquals(3, queryLong("SELECT COUNT(*) FROM Vault"));
        Assertions.assertEquals(5, queryLong("SELECT COUNT(*) FROM VaultItem"));
    }

    @Test
    void theDataStepCanRunAgainBeforeFinalising() throws SQLException, IOException {
        loadLegacyDatabase();
        runScript("sql/migrations/V2__playbook_structure.sql");
        runScript("sql/migrations/V3__playbook_data.sql");
        runScript("sql/migrations/V3__playbook_data.sql");
        runScript("sql/migrations/V4__playbook_finalise.sql");
        Assertions.assertEquals(3, queryLong("SELECT COUNT(*) FROM Vault WHERE ownerUuid IS NOT NULL"));
    }

    @Test
    void theItemColumnHoldsMoreThanSixtyFourKilobytes() throws SQLException, IOException {
        loadLegacyDatabase();
        migrate();
        byte[] large = new byte[100_000];
        Arrays.fill(large, (byte) 7);
        try (Connection connection = dataSource().getConnection()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO VaultItem (vaultUuid, slot, amount, item) VALUES (?, 9, 1, ?)")) {
                insert.setString(1, VAULT_ONE);
                insert.setBytes(2, large);
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(
                    "SELECT item FROM VaultItem WHERE vaultUuid = '" + VAULT_ONE + "' AND slot = 9")) {
                Assertions.assertTrue(row.next());
                Assertions.assertArrayEquals(large, row.getBytes("item"));
            }
        }
    }
}
