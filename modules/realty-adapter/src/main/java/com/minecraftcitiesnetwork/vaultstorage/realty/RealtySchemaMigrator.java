package com.minecraftcitiesnetwork.vaultstorage.realty;

import com.minecraftcitiesnetwork.pluginInfrastructure.sql.MigrationStep;
import com.minecraftcitiesnetwork.pluginInfrastructure.sql.SchemaMigrator;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/** The module's own schema, versioned apart from the host's so the two never read each other's. */
public final class RealtySchemaMigrator extends SchemaMigrator {

    public static final String RESOURCE_DIR = "sql/realty";

    /** Adding a migration means adding the V*.sql file and a step here. The list is not discovered. */
    public static final List<MigrationStep> MIGRATIONS = List.of(
            new MigrationStep(1, "pending sweep", "V1__pending_sweep.sql")
    );

    public void migrate(@NotNull DataSource dataSource, @NotNull Logger logger) throws SQLException {
        // The module's class loader: its scripts are in its jar, not the host's.
        migrate(dataSource, Path.of(RESOURCE_DIR), MIGRATIONS, RealtySchemaMigrator.class.getClassLoader(), logger);
    }

    @Override
    protected @NotNull String bootstrapDdl() {
        return """
                CREATE TABLE IF NOT EXISTS vaultstorage_realty_schema_version (
                    version INT PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    applied_at DATETIME NOT NULL DEFAULT NOW()
                )
                """;
    }

    @Override
    protected @NotNull String selectVersion() {
        return "SELECT COALESCE(MAX(version), 0) FROM vaultstorage_realty_schema_version";
    }

    @Override
    protected @NotNull String insertVersion() {
        return "INSERT INTO vaultstorage_realty_schema_version (version, description) VALUES (?, ?)";
    }
}
