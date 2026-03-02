package oscarvarto.mx.teststats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Paths;

/// Public helper API used by test code to enrich the current test execution.
///
/// ## Typical usage
///
/// ```java
/// TestStats.recordInput(Map.of("tenant", "acme", "attempt", 1));
/// TestStats.recordArtifactPath("trace", Path.of("artifacts", "trace.zip"));
/// ```
///
/// ## Contract
///
/// Calls are only valid while a JUnit test method is actively running under
/// [`TestStatisticsExtension`][TestStatisticsExtension]. If no execution is bound to the current
/// thread, the helper fails fast with `IllegalStateException`.
public final class TestStats {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private TestStats() {}

    /// Records a JSON payload exactly as input data for the current test execution.
    ///
    /// The string must parse as valid JSON. It is normalized into compact canonical JSON before
    /// being stored in the database.
    public static void recordInputJson(String json) {
        try {
            JsonNode tree = OBJECT_MAPPER.readTree(json);
            CurrentTestExecution.requireCurrent()
                    .addPayload("INPUT", "default", "application/json", OBJECT_MAPPER.writeValueAsString(tree));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Input JSON must be valid JSON.", e);
        }
    }

    /// Serializes an arbitrary Java object as JSON and records it as test input.
    public static void recordInput(Object value) {
        try {
            CurrentTestExecution.requireCurrent()
                    .addPayload("INPUT", "default", "application/json", OBJECT_MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize test input as JSON.", e);
        }
    }

    /// Records an artifact reference without a description.
    public static void recordArtifactPath(String kind, Path path) {
        recordArtifactPath(kind, path, null);
    }

    /// Records an artifact reference for the current execution.
    ///
    /// Relative paths are resolved against `test.statistics.artifacts.root` when that property is
    /// configured; otherwise the path is normalized to an absolute path.
    public static void recordArtifactPath(String kind, Path path, String description) {
        Path normalized = normalizeArtifactPath(path);
        CurrentTestExecution.requireCurrent().addArtifact(kind, normalized, description);
    }

    private static Path normalizeArtifactPath(Path path) {
        String root = System.getProperty("test.statistics.artifacts.root");
        if (root != null && !root.isBlank() && !path.isAbsolute()) {
            return Paths.get(root).resolve(path).normalize();
        }
        return path.toAbsolutePath().normalize();
    }
}
