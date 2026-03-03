package oscarvarto.mx.teststats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

@Isolated("Uses global test statistics system properties and embedded JUnit launchers.")
@Execution(ExecutionMode.SAME_THREAD)
class TestStatisticsExtensionIntegrationTest {

    private static final String FIXTURE_PROPERTY = "teststats.launcher-fixture.enabled";

    @Test
    void createsDatabaseAndAppliesMigrations() throws Exception {
        Path databasePath = temporaryDatabasePath();

        SummaryGeneratingListener summary = executeFixtures(databasePath, PassingFixture.class);

        assertThat(summary.getSummary().getTestsSucceededCount()).isEqualTo(1);
        assertThat(Files.exists(databasePath)).isTrue();
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM flyway_schema_history"))
                .isEqualTo(3);
        assertThat(queryForLong(
                        databasePath, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'view' AND name LIKE 'v_%'"))
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void capturesStatusesPayloadsArtifactsAndFailureDetails() throws Exception {
        Path databasePath = temporaryDatabasePath();

        SummaryGeneratingListener summary = executeFixtures(
                databasePath,
                PassingFixture.class,
                FailingFixture.class,
                DisabledFixture.class,
                PayloadArtifactFixture.class);

        assertThat(summary.getSummary().getTestsFoundCount()).isEqualTo(4);
        assertThat(summary.getSummary().getTestsStartedCount()).isEqualTo(3);
        assertThat(summary.getSummary().getTestsSkippedCount()).isEqualTo(1);
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution WHERE canonical_status = 'PASSED'"))
                .isEqualTo(2);
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution WHERE canonical_status = 'FAILED'"))
                .isEqualTo(1);
        assertThat(queryForLong(
                        databasePath, "SELECT COUNT(*) FROM test_execution WHERE canonical_status = 'DISABLED'"))
                .isEqualTo(1);
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution_payload"))
                .isEqualTo(1);
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution_artifact"))
                .isEqualTo(1);
        assertThat(queryForString(
                        databasePath, "SELECT disabled_reason FROM test_execution WHERE canonical_status = 'DISABLED'"))
                .isEqualTo("fixture disabled");
        assertThat(queryForString(
                        databasePath, "SELECT exception_class FROM test_execution WHERE canonical_status = 'FAILED'"))
                .isEqualTo(org.opentest4j.AssertionFailedError.class.getName());
        assertThat(queryForString(databasePath, "SELECT payload_text FROM test_execution_payload"))
                .contains("\"tenant\":\"acme\"");
        assertThat(queryForString(databasePath, "SELECT artifact_kind FROM test_execution_artifact"))
                .isEqualTo("trace");
    }

    @Test
    void updatesHistoryAndComputesDerivedViews() throws Exception {
        Path databasePath = temporaryDatabasePath();

        FlakyFixture.reset();
        SummaryGeneratingListener firstPass = executeFixtures(databasePath, PassingFixture.class);
        Thread.sleep(Duration.ofMillis(25));
        SummaryGeneratingListener secondPass = executeFixtures(databasePath, PassingFixture.class);
        SummaryGeneratingListener firstFlaky = executeFixtures(databasePath, FlakyFixture.class);
        SummaryGeneratingListener secondFlaky = executeFixtures(databasePath, FlakyFixture.class);
        SummaryGeneratingListener firstFingerprint =
                executeFixtures(databasePath, AlwaysFailingFingerprintFixture.class);
        SummaryGeneratingListener secondFingerprint =
                executeFixtures(databasePath, AlwaysFailingFingerprintFixture.class);

        assertThat(firstPass.getSummary().getTestsSucceededCount()).isEqualTo(1);
        assertThat(secondPass.getSummary().getTestsSucceededCount()).isEqualTo(1);
        assertThat(firstFlaky.getSummary().getTestsFailedCount()).isEqualTo(1);
        assertThat(secondFlaky.getSummary().getTestsSucceededCount()).isEqualTo(1);
        assertThat(firstFingerprint.getSummary().getTestsFailedCount()).isEqualTo(1);
        assertThat(secondFingerprint.getSummary().getTestsFailedCount()).isEqualTo(1);

        List<String> timestamps = queryForStrings(
                databasePath,
                "SELECT first_seen_at_utc, last_seen_at_utc FROM test_case WHERE class_name LIKE '%PassingFixture'");
        assertThat(timestamps).hasSize(2);
        assertThat(timestamps.get(1)).isGreaterThan(timestamps.get(0));
        assertThat(queryForLong(
                        databasePath,
                        "SELECT total_executions FROM v_test_case_quality WHERE class_name LIKE '%PassingFixture'"))
                .isEqualTo(2);
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM v_flaky_candidates"))
                .isEqualTo(1);
        assertThat(queryForString(
                        databasePath,
                        "SELECT latest_status FROM v_flaky_candidates WHERE class_name LIKE '%FlakyFixture'"))
                .isEqualTo("PASSED");
        assertThat(queryForLong(databasePath, "SELECT SUM(occurrence_count) FROM v_failure_fingerprints"))
                .isEqualTo(3);
        assertThat(queryForLong(
                        databasePath,
                        "SELECT fail_count FROM v_daily_status_trend ORDER BY execution_day_utc DESC LIMIT 1"))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void usesSingleRunRowWhenClassesExecuteConcurrently() throws Exception {
        Path databasePath = temporaryDatabasePath();

        SummaryGeneratingListener summary = executeFixtures(
                databasePath,
                ConcurrentFixtureA.class,
                ConcurrentFixtureB.class,
                ConcurrentFixtureC.class,
                ConcurrentFixtureD.class);

        assertThat(summary.getSummary().getTestsSucceededCount()).isEqualTo(4);
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_run")).isEqualTo(1);
        assertThat(queryForLong(databasePath, "SELECT COUNT(*) FROM test_execution"))
                .isEqualTo(4);
        assertThat(queryForLong(databasePath, "SELECT total_tests FROM v_run_summary"))
                .isEqualTo(4);
    }

    private SummaryGeneratingListener executeFixtures(Path databasePath, Class<?>... fixtureClasses) {
        String previousPath = System.getProperty(TestStatisticsDataSourceFactory.DB_PATH_PROPERTY);
        String previousEnabled = System.getProperty(TestStatisticsDataSourceFactory.ENABLED_PROPERTY);
        String previousFixtureEnabled = System.getProperty(FIXTURE_PROPERTY);
        try {
            System.setProperty(TestStatisticsDataSourceFactory.DB_PATH_PROPERTY, databasePath.toString());
            System.setProperty(TestStatisticsDataSourceFactory.ENABLED_PROPERTY, "true");
            System.setProperty(FIXTURE_PROPERTY, "true");

            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(java.util.Arrays.stream(fixtureClasses)
                            .map(DiscoverySelectors::selectClass)
                            .toArray(org.junit.platform.engine.DiscoverySelector[]::new))
                    .build();
            Launcher launcher = LauncherFactory.create();
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            launcher.registerTestExecutionListeners(listener);
            launcher.execute(request);
            return listener;
        } finally {
            restoreProperty(TestStatisticsDataSourceFactory.DB_PATH_PROPERTY, previousPath);
            restoreProperty(TestStatisticsDataSourceFactory.ENABLED_PROPERTY, previousEnabled);
            restoreProperty(FIXTURE_PROPERTY, previousFixtureEnabled);
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private Path temporaryDatabasePath() throws Exception {
        Path directory = Files.createTempDirectory("test-statistics-");
        return directory.resolve("test-statistics.db");
    }

    private long queryForLong(Path databasePath, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:turso:" + databasePath);
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private String queryForString(Path databasePath, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:turso:" + databasePath);
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private List<String> queryForStrings(Path databasePath, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:turso:" + databasePath);
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return List.of();
            }
            return List.of(resultSet.getString(1), resultSet.getString(2));
        }
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class PassingFixture {
        @Test
        void passes() {}
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class FailingFixture {
        @Test
        void fails() {
            fail("boom");
        }
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class DisabledFixture {
        @Disabled("fixture disabled")
        @Test
        void disabled() {}
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class PayloadArtifactFixture {
        @Test
        void recordsPayloadAndArtifact() {
            TestStats.recordInput(Map.of("tenant", "acme", "attempt", 1));
            TestStats.recordArtifactPath("trace", Path.of("artifacts", "trace.zip"), "Playwright trace");
        }
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class FlakyFixture {
        private static final AtomicInteger ATTEMPTS = new AtomicInteger();

        static void reset() {
            ATTEMPTS.set(0);
        }

        @Test
        void flipsFromFailToPass() {
            if (ATTEMPTS.getAndIncrement() == 0) {
                fail("transient issue");
            }
        }
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class AlwaysFailingFingerprintFixture {
        @Test
        void failsWithSameFingerprint() {
            throw new IllegalStateException("fingerprint failure");
        }
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class ConcurrentFixtureA {
        @Test
        void executes() throws Exception {
            Thread.sleep(100);
        }
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class ConcurrentFixtureB {
        @Test
        void executes() throws Exception {
            Thread.sleep(100);
        }
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class ConcurrentFixtureC {
        @Test
        void executes() throws Exception {
            Thread.sleep(100);
        }
    }

    @EnabledIfSystemProperty(named = FIXTURE_PROPERTY, matches = "true")
    public static class ConcurrentFixtureD {
        @Test
        void executes() throws Exception {
            Thread.sleep(100);
        }
    }
}
