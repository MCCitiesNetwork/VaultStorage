package com.minecraftcitiesnetwork.vaultstorage.database;

import com.minecraftcitiesnetwork.pluginInfrastructure.sql.MigrationStep;
import com.minecraftcitiesnetwork.pluginInfrastructure.sql.SchemaMigrator;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** Brings the database to the schema this version of the plugin expects. */
public final class VaultStorageSchemaMigrator extends SchemaMigrator {

    public static final String RESOURCE_DIR = "sql/migrations";

    /** Adding a migration means adding the V*.sql file and a step here. The list is not discovered. */
    public static final List<MigrationStep> MIGRATIONS = List.of(
            new MigrationStep(1, "legacy baseline", "V1__legacy_baseline.sql"),
            new MigrationStep(2, "playbook structure", "V2__playbook_structure.sql"),
            new MigrationStep(3, "playbook data", "V3__playbook_data.sql"),
            new MigrationStep(4, "playbook finalise", "V4__playbook_finalise.sql")
    );

    /** Applies every migration the database has not had yet. */
    public void migrate(@NotNull DataSource dataSource, @NotNull Logger logger) throws SQLException {
        migrate(dataSource, Path.of(RESOURCE_DIR), MIGRATIONS,
                VaultStorageSchemaMigrator.class.getClassLoader(), logger);
    }

    /** The statements of a script, split the way a migration is. */
    public @NotNull List<String> statements(@NotNull String script) {
        return splitStatements(script);
    }

    @Override
    protected @NotNull String bootstrapDdl() {
        return """
                CREATE TABLE IF NOT EXISTS vaultstorage_schema_version (
                    version INT PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    applied_at DATETIME NOT NULL DEFAULT NOW()
                )
                """;
    }

    @Override
    protected @NotNull String selectVersion() {
        return "SELECT COALESCE(MAX(version), 0) FROM vaultstorage_schema_version";
    }

    @Override
    protected @NotNull String insertVersion() {
        return "INSERT INTO vaultstorage_schema_version (version, description) VALUES (?, ?)";
    }
}
