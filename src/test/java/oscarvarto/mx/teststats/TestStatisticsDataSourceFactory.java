package oscarvarto.mx.teststats;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/// Resolves the statistics database location and exposes the JDBC entry points used by the
/// test-statistics subsystem.
///
/// ## Driver split
///
/// The implementation intentionally uses two JDBC URLs against the same database file:
///
/// - `jdbc:turso:` for runtime inserts and updates performed by the JUnit extension
/// - `jdbc:sqlite:` for Flyway migrations and schema inspection
///
/// This keeps the write path aligned with the Turso driver while delegating migration bookkeeping
/// to Flyway on top of the mature SQLite/Xerial integration.
///
/// The factory currently wraps `DriverManager` directly instead of relying on the preview Turso
/// `DataSource` type because the direct JDBC path has been more predictable in local runs.
final class TestStatisticsDataSourceFactory {

    static final String ENABLED_PROPERTY = "test.statistics.enabled";
    static final String DB_PATH_PROPERTY = "test.statistics.db.path";
    static final String RUN_LABEL_PROPERTY = "test.statistics.run.label";
    private static final String DEFAULT_DB_PATH = "src/test/resources/test-statistics.db";

    /// Returns whether the statistics pipeline should be active for the current JVM.
    boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    /// Resolves the effective database path from the working directory and system properties.
    Path resolveDatabasePath() {
        Path workingDirectory =
                Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String configuredPath = System.getProperty(DB_PATH_PROPERTY);
        Path databasePath = configuredPath == null || configuredPath.isBlank()
                ? workingDirectory.resolve(DEFAULT_DB_PATH)
                : Paths.get(configuredPath);
        if (!databasePath.isAbsolute()) {
            databasePath = workingDirectory.resolve(databasePath);
        }
        return databasePath.normalize();
    }

    /// Creates a `DataSource` for the Turso-backed runtime write path, creating parent directories
    /// on demand.
    DataSource createDataSource() {
        Path databasePath = resolveDatabasePath();
        ensureParentDirectory(databasePath);
        String jdbcUrl = tursoJdbcUrl(databasePath);
        return new DriverManagerDataSource(jdbcUrl);
    }

    /// Returns the Turso JDBC URL used by the extension while persisting test-execution data.
    String tursoJdbcUrl() {
        return tursoJdbcUrl(resolveDatabasePath());
    }

    /// Returns the SQLite JDBC URL used by Flyway for schema migrations against the same file.
    String sqliteJdbcUrl() {
        Path databasePath = resolveDatabasePath();
        ensureParentDirectory(databasePath);
        return "jdbc:sqlite:" + databasePath;
    }

    private void ensureParentDirectory(Path databasePath) {
        try {
            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not create directory for statistics database: " + databasePath, e);
        }
    }

    private String tursoJdbcUrl(Path databasePath) {
        return "jdbc:turso:" + databasePath;
    }

    /// Minimal `DataSource` backed by `DriverManager`.
    ///
    /// The implementation stays deliberately small because the statistics extension only needs
    /// short-lived local connections during test execution.
    private static final class DriverManagerDataSource implements DataSource {

        private final String jdbcUrl;

        private DriverManagerDataSource(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(jdbcUrl);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return DriverManager.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            DriverManager.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            DriverManager.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return DriverManager.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("Parent logger is not supported.");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Unsupported unwrap type: " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
