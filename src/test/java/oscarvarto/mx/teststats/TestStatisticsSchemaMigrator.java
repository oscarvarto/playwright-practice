package oscarvarto.mx.teststats;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/// Applies schema and view migrations for the test statistics database through Flyway.
///
/// ## Migration strategy
///
/// Versioned SQL files remain under `src/test/resources/test-statistics/schema`. Flyway executes
/// them against the local database file through the SQLite/Xerial driver and records migration
/// state in its built-in `flyway_schema_history` table.
///
/// Runtime test-statistics writes still use the Turso JDBC driver. This class exists specifically
/// so schema management can rely on Flyway without requiring Flyway to understand `jdbc:turso:`
/// directly.
///
/// ## Legacy transition
///
/// Databases created before the Flyway switch are identified by the presence of the legacy
/// `schema_migrations` table and the absence of `flyway_schema_history`. Those databases are
/// baselined at version `2` so Flyway can take over without replaying already-applied migrations.
final class TestStatisticsSchemaMigrator {

    /// Applies any pending migrations to the target database.
    void migrate(TestStatisticsDataSourceFactory dataSourceFactory) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSourceFactory.sqliteJdbcUrl(), null, null)
                .locations("classpath:test-statistics/schema")
                .validateMigrationNaming(true);

        if (shouldBaselineLegacyDatabase(dataSourceFactory)) {
            configuration.baselineOnMigrate(true).baselineVersion("2").baselineDescription("legacy custom migrator");
        }

        configuration.load().migrate();
    }

    private boolean shouldBaselineLegacyDatabase(TestStatisticsDataSourceFactory dataSourceFactory) {
        try (Connection connection = java.sql.DriverManager.getConnection(dataSourceFactory.sqliteJdbcUrl())) {
            return tableExists(connection, "schema_migrations") && !tableExists(connection, "flyway_schema_history");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to inspect existing schema state for database: " + dataSourceFactory.resolveDatabasePath(),
                    e);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var statement =
                        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?");
                ResultSet resultSet = execute(statement, tableName)) {
            return resultSet.next();
        }
    }

    private ResultSet execute(java.sql.PreparedStatement statement, String value) throws SQLException {
        statement.setString(1, value);
        return statement.executeQuery();
    }
}
