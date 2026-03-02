package oscarvarto.mx.teststats;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;

/// Central write-side gateway for the test statistics schema.
///
/// This class owns all mutations of the database and intentionally keeps the rest of the extension
/// code free from ad hoc SQL. Its responsibilities are:
///
/// - trigger Flyway-backed schema initialization before the first write
/// - `test_run` lifecycle writes
/// - `test_case` upserts
/// - `test_execution` inserts and updates
/// - payload and artifact persistence
/// - failure fingerprint derivation
///
/// Runtime reads and writes happen through the Turso JDBC driver exposed by
/// [`TestStatisticsDataSourceFactory`][TestStatisticsDataSourceFactory], while migration execution
/// is delegated to [`TestStatisticsSchemaMigrator`][TestStatisticsSchemaMigrator].
final class TestStatisticsRepository {

    private final TestStatisticsDataSourceFactory dataSourceFactory;
    private final TestStatisticsSchemaMigrator schemaMigrator;

    TestStatisticsRepository(
            TestStatisticsDataSourceFactory dataSourceFactory, TestStatisticsSchemaMigrator schemaMigrator) {
        this.dataSourceFactory = dataSourceFactory;
        this.schemaMigrator = schemaMigrator;
    }

    /// Ensures the target database has the latest raw schema and views before any writes occur.
    ///
    /// Flyway migration execution is intentionally performed before the extension opens its first
    /// Turso-backed write connection.
    synchronized void initialize() {
        schemaMigrator.migrate(dataSourceFactory);
    }

    /// Inserts the `test_run` row for the current launcher session.
    synchronized void createRun(TestRunContextCollector.TestRunContext runContext) {
        try (Connection connection = dataSourceFactory.createDataSource().getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT OR IGNORE INTO test_run(
                      run_id,
                      framework,
                      runner,
                      started_at_utc,
                      trigger_source,
                      run_label,
                      working_directory,
                      git_commit,
                      git_branch,
                      ci_provider,
                      ci_build_id,
                      ci_job_name,
                      host_name,
                      os_name,
                      os_arch,
                      jvm_vendor,
                      jvm_version,
                      browser_name,
                      browser_channel,
                      headless
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                int parameterIndex = 1;
                statement.setString(parameterIndex++, runContext.runId());
                statement.setString(parameterIndex++, runContext.framework());
                statement.setString(parameterIndex++, runContext.runner());
                statement.setString(parameterIndex++, runContext.startedAt().toString());
                statement.setString(parameterIndex++, runContext.triggerSource());
                statement.setString(parameterIndex++, runContext.runLabel());
                statement.setString(parameterIndex++, runContext.workingDirectory());
                statement.setString(parameterIndex++, runContext.gitCommit());
                statement.setString(parameterIndex++, runContext.gitBranch());
                statement.setString(parameterIndex++, runContext.ciProvider());
                statement.setString(parameterIndex++, runContext.ciBuildId());
                statement.setString(parameterIndex++, runContext.ciJobName());
                statement.setString(parameterIndex++, runContext.hostName());
                statement.setString(parameterIndex++, runContext.osName());
                statement.setString(parameterIndex++, runContext.osArch());
                statement.setString(parameterIndex++, runContext.jvmVendor());
                statement.setString(parameterIndex++, runContext.jvmVersion());
                statement.setString(parameterIndex++, runContext.browserName());
                statement.setString(parameterIndex++, runContext.browserChannel());
                if (runContext.headless() == null) {
                    statement.setNull(parameterIndex, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(parameterIndex, runContext.headless());
                }
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create statistics test run.", e);
        }
    }

    /// Marks the run as finished when the root JUnit store is closed.
    synchronized void finishRun(String runId, Instant finishedAt) {
        try (Connection connection = dataSourceFactory.createDataSource().getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("UPDATE test_run SET finished_at_utc = ? WHERE run_id = ?")) {
            statement.setString(1, finishedAt.toString());
            statement.setString(2, runId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to finish statistics test run.", e);
        }
    }

    /// Creates the initial `test_execution` row before a test body starts running.
    synchronized void startExecution(
            TestRunContextCollector.TestRunContext runContext,
            TestRunContextCollector.TestCaseDetails testCaseDetails,
            String executionId,
            Instant startedAt,
            String threadName) {
        String startedAtUtc = startedAt.toString();
        try (Connection connection = dataSourceFactory.createDataSource().getConnection()) {
            connection.setAutoCommit(false);
            upsertTestCase(connection, testCaseDetails, startedAtUtc);
            int attemptIndex = nextAttemptIndex(connection, runContext.runId(), testCaseDetails.testCaseId());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO test_execution(
                      execution_id,
                      run_id,
                      test_case_id,
                      attempt_index,
                      started_at_utc,
                      finished_at_utc,
                      duration_ms,
                      canonical_status,
                      framework_status,
                      disabled_reason,
                      exception_class,
                      exception_message,
                      exception_stacktrace,
                      failure_fingerprint,
                      thread_name,
                      created_at_utc
                    ) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
                    """)) {
                statement.setString(1, executionId);
                statement.setString(2, runContext.runId());
                statement.setString(3, testCaseDetails.testCaseId());
                statement.setInt(4, attemptIndex);
                statement.setString(5, startedAtUtc);
                statement.setString(6, CanonicalStatus.UNKNOWN.name());
                statement.setString(7, "STARTED");
                statement.setString(8, threadName);
                statement.setString(9, startedAtUtc);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to start test execution statistics row.", e);
        }
    }

    /// Finalizes a started execution with its final status, timings, diagnostics, payloads, and
    /// artifact references.
    synchronized void finishExecution(
            CurrentTestExecution execution,
            CanonicalStatus status,
            String frameworkStatus,
            Throwable throwable,
            String threadName) {
        execution.ensureFinished();
        FailureDetails failureDetails = FailureDetails.fromThrowable(throwable);
        try (Connection connection = dataSourceFactory.createDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE test_execution
                    SET finished_at_utc = ?,
                        duration_ms = ?,
                        canonical_status = ?,
                        framework_status = ?,
                        disabled_reason = NULL,
                        exception_class = ?,
                        exception_message = ?,
                        exception_stacktrace = ?,
                        failure_fingerprint = ?,
                        thread_name = ?
                    WHERE execution_id = ?
                    """)) {
                statement.setString(1, execution.finishedAt().toString());
                if (execution.durationMs() == null) {
                    statement.setNull(2, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(2, execution.durationMs());
                }
                statement.setString(3, status.name());
                statement.setString(4, frameworkStatus);
                statement.setString(5, failureDetails.exceptionClass());
                statement.setString(6, failureDetails.exceptionMessage());
                statement.setString(7, failureDetails.exceptionStacktrace());
                statement.setString(8, failureDetails.failureFingerprint());
                statement.setString(9, threadName);
                statement.setString(10, execution.executionId());
                statement.executeUpdate();
            }
            insertPayloads(connection, execution);
            insertArtifacts(connection, execution);
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to finalize test execution statistics row.", e);
        }
    }

    /// Records a disabled test directly, without using the thread-local execution staging path.
    synchronized void recordDisabledExecution(
            TestRunContextCollector.TestRunContext runContext,
            TestRunContextCollector.TestCaseDetails testCaseDetails,
            String disabledReason,
            String threadName) {
        Instant now = Instant.now();
        String nowUtc = now.toString();
        try (Connection connection = dataSourceFactory.createDataSource().getConnection()) {
            connection.setAutoCommit(false);
            upsertTestCase(connection, testCaseDetails, nowUtc);
            int attemptIndex = nextAttemptIndex(connection, runContext.runId(), testCaseDetails.testCaseId());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO test_execution(
                      execution_id,
                      run_id,
                      test_case_id,
                      attempt_index,
                      started_at_utc,
                      finished_at_utc,
                      duration_ms,
                      canonical_status,
                      framework_status,
                      disabled_reason,
                      exception_class,
                      exception_message,
                      exception_stacktrace,
                      failure_fingerprint,
                      thread_name,
                      created_at_utc
                    ) VALUES (?, ?, ?, ?, NULL, NULL, NULL, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?)
                    """)) {
                statement.setString(1, java.util.UUID.randomUUID().toString());
                statement.setString(2, runContext.runId());
                statement.setString(3, testCaseDetails.testCaseId());
                statement.setInt(4, attemptIndex);
                statement.setString(5, CanonicalStatus.DISABLED.name());
                statement.setString(6, "DISABLED");
                statement.setString(7, disabledReason);
                statement.setString(8, threadName);
                statement.setString(9, nowUtc);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record disabled test statistics row.", e);
        }
    }

    private void upsertTestCase(
            Connection connection, TestRunContextCollector.TestCaseDetails testCaseDetails, String seenAtUtc)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case(
                  test_case_id,
                  framework,
                  engine_id,
                  unique_id,
                  package_name,
                  class_name,
                  method_name,
                  display_name,
                  first_seen_at_utc,
                  last_seen_at_utc
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(test_case_id) DO UPDATE SET
                  package_name = excluded.package_name,
                  class_name = excluded.class_name,
                  method_name = excluded.method_name,
                  display_name = excluded.display_name,
                  last_seen_at_utc = excluded.last_seen_at_utc
                """)) {
            int parameterIndex = 1;
            statement.setString(parameterIndex++, testCaseDetails.testCaseId());
            statement.setString(parameterIndex++, testCaseDetails.framework());
            statement.setString(parameterIndex++, testCaseDetails.engineId());
            statement.setString(parameterIndex++, testCaseDetails.uniqueId());
            statement.setString(parameterIndex++, testCaseDetails.packageName());
            statement.setString(parameterIndex++, testCaseDetails.className());
            statement.setString(parameterIndex++, testCaseDetails.methodName());
            statement.setString(parameterIndex++, testCaseDetails.displayName());
            statement.setString(parameterIndex++, seenAtUtc);
            statement.setString(parameterIndex, seenAtUtc);
            statement.executeUpdate();
        }

        try (PreparedStatement statement =
                connection.prepareStatement("INSERT OR IGNORE INTO test_case_tag(test_case_id, tag) VALUES (?, ?)")) {
            for (String tag : testCaseDetails.tags()) {
                statement.setString(1, testCaseDetails.testCaseId());
                statement.setString(2, tag);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private int nextAttemptIndex(Connection connection, String runId, String testCaseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(attempt_index), 0) + 1 FROM test_execution WHERE run_id = ? AND test_case_id = ?")) {
            statement.setString(1, runId);
            statement.setString(2, testCaseId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 1;
            }
        }
    }

    private void insertPayloads(Connection connection, CurrentTestExecution execution) throws SQLException {
        if (execution.payloads().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution_payload(
                  payload_id,
                  execution_id,
                  payload_role,
                  payload_name,
                  content_type,
                  payload_text,
                  created_at_utc
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (CurrentTestExecution.PayloadRecord payload : execution.payloads()) {
                statement.setString(1, payload.payloadId());
                statement.setString(2, payload.executionId());
                statement.setString(3, payload.payloadRole());
                statement.setString(4, payload.payloadName());
                statement.setString(5, payload.contentType());
                statement.setString(6, payload.payloadText());
                statement.setString(7, payload.createdAt().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertArtifacts(Connection connection, CurrentTestExecution execution) throws SQLException {
        if (execution.artifacts().isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution_artifact(
                  artifact_id,
                  execution_id,
                  artifact_kind,
                  artifact_path,
                  description,
                  created_at_utc
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            for (CurrentTestExecution.ArtifactRecord artifact : execution.artifacts()) {
                statement.setString(1, artifact.artifactId());
                statement.setString(2, artifact.executionId());
                statement.setString(3, artifact.artifactKind());
                statement.setString(4, artifact.artifactPath());
                statement.setString(5, artifact.description());
                statement.setString(6, artifact.createdAt().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /// Failure diagnostics as stored in the execution row.
    private record FailureDetails(
            String exceptionClass, String exceptionMessage, String exceptionStacktrace, String failureFingerprint) {

        /// Builds persisted failure details, including a fingerprint suitable for grouping repeated
        /// failures across runs.
        static FailureDetails fromThrowable(Throwable throwable) {
            if (throwable == null) {
                return new FailureDetails(null, null, null, null);
            }
            String exceptionClass = throwable.getClass().getName();
            String exceptionMessage = normalizeMessage(throwable.getMessage());
            String exceptionStacktrace = stacktraceOf(throwable);
            String preferredFrame = preferredFrame(throwable);
            String fingerprintSource = exceptionClass + "|" + preferredFrame + "|" + exceptionMessage;
            return new FailureDetails(
                    exceptionClass, exceptionMessage, exceptionStacktrace, sha256Hex(fingerprintSource));
        }

        private static String normalizeMessage(String message) {
            if (message == null || message.isBlank()) {
                return null;
            }
            String firstLine = message.lines().findFirst().orElse(message).trim();
            return firstLine.isEmpty() ? null : firstLine;
        }

        private static String preferredFrame(Throwable throwable) {
            StackTraceElement fallback = null;
            for (StackTraceElement element : throwable.getStackTrace()) {
                String className = element.getClassName();
                if (className.startsWith("oscarvarto.mx")) {
                    return formatFrame(element);
                }
                if (fallback == null
                        && !className.startsWith("org.junit.")
                        && !className.startsWith("java.")
                        && !className.startsWith("jdk.")
                        && !className.startsWith("sun.")) {
                    fallback = element;
                }
            }
            return fallback == null ? null : formatFrame(fallback);
        }

        private static String formatFrame(StackTraceElement element) {
            return element.getClassName() + "#" + element.getMethodName() + ":" + element.getLineNumber();
        }

        private static String stacktraceOf(Throwable throwable) {
            StringWriter writer = new StringWriter();
            throwable.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        }

        private static String sha256Hex(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available.", e);
            }
        }
    }
}
