package oscarvarto.mx.teststats;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/// Thread-local staging area for the execution that is currently running on a JUnit worker thread.
///
/// ## Why this exists
///
/// JUnit lifecycle callbacks know *when* an execution starts and finishes, but test code itself
/// is the only place that knows about optional payloads and artifact paths worth persisting.
/// This class bridges those two worlds:
///
/// - the extension creates and binds the current execution before the test body runs
/// - test code enriches that execution through [`TestStats`][TestStats]
/// - the extension flushes the staged data to the repository after the final outcome is known
///
/// The thread-local model fits the current execution strategy because individual test methods run
/// on one thread at a time, even when test classes execute concurrently.
final class CurrentTestExecution {

    private static final ThreadLocal<CurrentTestExecution> CURRENT = new ThreadLocal<>();

    private final String executionId;
    private final String runId;
    private final String testCaseId;
    private final Instant startedAt;
    private final List<PayloadRecord> payloads = new ArrayList<>();
    private final List<ArtifactRecord> artifacts = new ArrayList<>();

    private Instant finishedAt;
    private Long durationMs;

    private CurrentTestExecution(String executionId, String runId, String testCaseId, Instant startedAt) {
        this.executionId = executionId;
        this.runId = runId;
        this.testCaseId = testCaseId;
        this.startedAt = startedAt;
    }

    /// Binds a newly started execution to the current thread.
    static CurrentTestExecution begin(String executionId, String runId, String testCaseId, Instant startedAt) {
        CurrentTestExecution execution = new CurrentTestExecution(executionId, runId, testCaseId, startedAt);
        CURRENT.set(execution);
        return execution;
    }

    /// Returns the execution currently bound to the thread, if any.
    static Optional<CurrentTestExecution> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /// Returns the current execution or fails fast when no test is active.
    static CurrentTestExecution requireCurrent() {
        CurrentTestExecution execution = CURRENT.get();
        if (execution == null) {
            throw new IllegalStateException(
                    "Test statistics are only available while a JUnit test is actively running.");
        }
        return execution;
    }

    static void clear() {
        CURRENT.remove();
    }

    String executionId() {
        return executionId;
    }

    String runId() {
        return runId;
    }

    String testCaseId() {
        return testCaseId;
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant finishedAt() {
        return finishedAt;
    }

    Long durationMs() {
        return durationMs;
    }

    List<PayloadRecord> payloads() {
        return List.copyOf(payloads);
    }

    List<ArtifactRecord> artifacts() {
        return List.copyOf(artifacts);
    }

    /// Marks the execution as finished and computes its duration once.
    void markFinished(Instant finishedAt) {
        if (this.finishedAt != null) {
            return;
        }
        this.finishedAt = finishedAt;
        this.durationMs = Math.max(0L, Duration.between(startedAt, finishedAt).toMillis());
    }

    /// Ensures an execution has finish metadata before it is flushed to the database.
    void ensureFinished() {
        if (finishedAt == null) {
            markFinished(Instant.now());
        }
    }

    /// Stages a payload that should be written to `test_execution_payload` when the test completes.
    void addPayload(String payloadRole, String payloadName, String contentType, String payloadText) {
        payloads.add(new PayloadRecord(
                UUID.randomUUID().toString(),
                executionId,
                payloadRole,
                payloadName,
                contentType,
                payloadText,
                Instant.now()));
    }

    /// Stages an artifact reference that should be written to `test_execution_artifact`.
    void addArtifact(String artifactKind, Path artifactPath, String description) {
        artifacts.add(new ArtifactRecord(
                UUID.randomUUID().toString(),
                executionId,
                artifactKind,
                artifactPath.toString(),
                description,
                Instant.now()));
    }

    /// Serialized payload metadata awaiting persistence.
    record PayloadRecord(
            String payloadId,
            String executionId,
            String payloadRole,
            String payloadName,
            String contentType,
            String payloadText,
            Instant createdAt) {}

    /// Artifact reference metadata awaiting persistence.
    record ArtifactRecord(
            String artifactId,
            String executionId,
            String artifactKind,
            String artifactPath,
            String description,
            Instant createdAt) {}
}
