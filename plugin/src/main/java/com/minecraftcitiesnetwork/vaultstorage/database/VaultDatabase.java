package com.minecraftcitiesnetwork.vaultstorage.database;

import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The connection pool and the MyBatis session factory over it. */
public final class VaultDatabase implements AutoCloseable {

    /** Matches the size of the plugin's database executor. */
    public static final int POOL_SIZE = 4;

    private final @NotNull DataSource dataSource;
    private final @Nullable PooledDataSource pool;
    private final @NotNull SqlSessionFactory sessionFactory;

    public VaultDatabase(@NotNull DatabaseSettings settings) {
        PooledDataSource pooled = new PooledDataSource("org.mariadb.jdbc.Driver", settings.jdbcUrl(),
                settings.username(), settings.password());
        // A connection the server dropped while idle is replaced instead of handed out.
        pooled.setPoolPingEnabled(true);
        pooled.setPoolPingQuery("SELECT 1");
        pooled.setPoolPingConnectionsNotUsedFor(600000);
        pooled.setPoolMaximumActiveConnections(POOL_SIZE);
        pooled.setPoolMaximumIdleConnections(POOL_SIZE);
        this.pool = pooled;
        this.dataSource = pooled;
        this.sessionFactory = buildFactory(pooled);
    }

    /** For tests: uses a data source someone else owns. */
    public VaultDatabase(@NotNull DataSource dataSource) {
        this.pool = null;
        this.dataSource = dataSource;
        this.sessionFactory = buildFactory(dataSource);
    }

    private static @NotNull SqlSessionFactory buildFactory(@NotNull DataSource dataSource) {
        Environment environment = new Environment("production", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        // Before addMapper: MyBatis has no built-in handler for UUID.
        configuration.getTypeHandlerRegistry().register(UUID.class, UuidHandler.class);
        // A new mapper is added here; the list is not discovered.
        configuration.addMapper(MariaVaultMapper.class);
        configuration.addMapper(MariaVaultItemMapper.class);
        configuration.addMapper(MariaWorldMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * Adds a mapper a feature module owns. Does nothing if it is already there, so a module can
     * be started again without a restart.
     */
    public void registerMapper(@NotNull Class<?> mapper) {
        Configuration configuration = this.sessionFactory.getConfiguration();
        if (!configuration.hasMapper(mapper)) {
            configuration.addMapper(mapper);
        }
    }

    public @NotNull DataSource dataSource() {
        return this.dataSource;
    }

    /** A transactional session: nothing is kept unless {@code session().commit()} is called. */
    public @NotNull SqlSessionWrapper openSession() {
        return new MariaSqlSession(this.sessionFactory.openSession());
    }

    /** With {@code true}, a session for a single statement or a read. */
    public @NotNull SqlSessionWrapper openSession(boolean autoCommit) {
        return new MariaSqlSession(this.sessionFactory.openSession(autoCommit));
    }

    @Override
    public void close() {
        if (this.pool != null) {
            this.pool.forceCloseAll();
        }
    }
}
