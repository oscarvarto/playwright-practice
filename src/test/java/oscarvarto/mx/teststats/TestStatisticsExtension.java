package oscarvarto.mx.teststats;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/// Global JUnit 5 extension that captures execution statistics into the local Turso database.
///
/// ## Lifecycle responsibilities
///
/// - initialize the database schema once per launcher session
/// - create one `test_run` row per launcher session
/// - create one `test_execution` row per actual test attempt
/// - map JUnit outcomes into canonical statuses
/// - flush staged payloads and artifact references from [`CurrentTestExecution`][CurrentTestExecution]
///
/// The extension is auto-registered through JUnit's service loader configuration in
/// `META-INF/services/org.junit.jupiter.api.extension.Extension`.
public final class TestStatisticsExtension
        implements BeforeAllCallback,
                AfterAllCallback,
                BeforeTestExecutionCallback,
                org.junit.jupiter.api.extension.AfterTestExecutionCallback,
                TestWatcher {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(TestStatisticsExtension.class);

    /// Forces session initialization before the first test class in the launcher session executes.
    @Override
    public void beforeAll(ExtensionContext context) {
        session(context);
    }

    /// Accesses the root session so JUnit keeps the closeable resource alive until the launcher
    /// session ends.
    @Override
    public void afterAll(ExtensionContext context) {
        session(context);
    }

    /// Opens a new execution attempt and binds it to the current worker thread.
    @Override
    public void beforeTestExecution(ExtensionContext context) {
        SessionResource session = session(context);
        if (!session.enabled()) {
            return;
        }

        Instant startedAt = Instant.now();
        TestRunContextCollector.TestCaseDetails testCaseDetails =
                session.collector().collectTestCaseDetails(context);
        String executionId = UUID.randomUUID().toString();
        session.repository()
                .startExecution(
                        session.runContext(),
                        testCaseDetails,
                        executionId,
                        startedAt,
                        Thread.currentThread().getName());
        CurrentTestExecution.begin(executionId, session.runContext().runId(), testCaseDetails.testCaseId(), startedAt);
    }

    /// Records the finish timestamp as close as possible to the actual end of the test body.
    @Override
    public void afterTestExecution(ExtensionContext context) {
        CurrentTestExecution.current().ifPresent(execution -> execution.markFinished(Instant.now()));
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        complete(context, CanonicalStatus.PASSED, "SUCCESSFUL", null);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        complete(context, CanonicalStatus.ABORTED, "ABORTED", cause);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        complete(context, CanonicalStatus.FAILED, "FAILED", cause);
    }

    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        SessionResource session = session(context);
        if (!session.enabled()) {
            return;
        }
        TestRunContextCollector.TestCaseDetails testCaseDetails =
                session.collector().collectTestCaseDetails(context);
        session.repository()
                .recordDisabledExecution(
                        session.runContext(),
                        testCaseDetails,
                        reason.orElse(null),
                        Thread.currentThread().getName());
    }

    /// Finalizes the current execution after JUnit reports the test outcome.
    private void complete(ExtensionContext context, CanonicalStatus status, String frameworkStatus, Throwable cause) {
        SessionResource session = session(context);
        if (!session.enabled()) {
            CurrentTestExecution.clear();
            return;
        }

        CurrentTestExecution execution = CurrentTestExecution.current().orElse(null);
        if (execution == null) {
            return;
        }

        execution.ensureFinished();
        session.repository()
                .finishExecution(
                        execution,
                        status,
                        frameworkStatus,
                        cause,
                        Thread.currentThread().getName());
        CurrentTestExecution.clear();
    }

    private SessionResource session(ExtensionContext context) {
        return context.getRoot()
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(
                        SessionResource.class, ignored -> SessionResource.create(), SessionResource.class);
    }

    /// Root-scoped resource shared by the full JUnit launcher session.
    ///
    /// The root store guarantees that:
    ///
    /// - schema initialization happens once
    /// - only one `test_run` row is created per launcher session
    /// - the run is marked finished when the store is closed
    private static final class SessionResource implements ExtensionContext.Store.CloseableResource {

        private final boolean enabled;
        private final TestStatisticsRepository repository;
        private final TestRunContextCollector collector;
        private final TestRunContextCollector.TestRunContext runContext;

        private SessionResource(
                boolean enabled,
                TestStatisticsRepository repository,
                TestRunContextCollector collector,
                TestRunContextCollector.TestRunContext runContext) {
            this.enabled = enabled;
            this.repository = repository;
            this.collector = collector;
            this.runContext = runContext;
        }

        /// Creates the root session, runs Flyway migrations, and eagerly writes the `test_run`
        /// row when the feature is enabled.
        static SessionResource create() {
            TestStatisticsDataSourceFactory factory = new TestStatisticsDataSourceFactory();
            if (!factory.isEnabled()) {
                return new SessionResource(false, null, null, null);
            }

            TestStatisticsRepository repository =
                    new TestStatisticsRepository(factory, new TestStatisticsSchemaMigrator());
            repository.initialize();

            TestRunContextCollector collector = new TestRunContextCollector();
            TestRunContextCollector.TestRunContext runContext = collector.collectRunContext(
                    Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize());
            repository.createRun(runContext);
            return new SessionResource(true, repository, collector, runContext);
        }

        boolean enabled() {
            return enabled;
        }

        TestStatisticsRepository repository() {
            return repository;
        }

        TestRunContextCollector collector() {
            return collector;
        }

        TestRunContextCollector.TestRunContext runContext() {
            return runContext;
        }

        @Override
        public void close() {
            if (enabled) {
                repository.finishRun(runContext.runId(), Instant.now());
            }
        }
    }
}
