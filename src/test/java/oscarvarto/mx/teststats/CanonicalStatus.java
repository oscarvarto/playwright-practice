package oscarvarto.mx.teststats;

/// Canonical execution states stored in `test_execution.canonical_status`.
///
/// The enum is intentionally broader than the current JUnit 5 implementation so the
/// database schema can stay stable when additional adapters are introduced later.
enum CanonicalStatus {
    PASSED,
    FAILED,
    ABORTED,
    DISABLED,
    SKIPPED,
    BLOCKED,
    UNKNOWN
}
