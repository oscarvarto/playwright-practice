package oscarvarto.mx.teststats;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.ExtensionContext;

/// Collects metadata that should be attached to runs and logical test cases.
///
/// ## Run metadata
///
/// For each launcher session the collector captures:
///
/// - working directory
/// - host / OS / JVM information
/// - CI metadata when available
/// - Git branch / commit, falling back to `git` commands for local runs
/// - Playwright browser settings from system properties or `playwright.json`
///
/// ## Test identity
///
/// The collector derives a stable logical test identity from JUnit's unique ID and stores both the
/// raw unique ID and its SHA-256 hash.
final class TestRunContextCollector {

    private static final Pattern ENGINE_ID_PATTERN = Pattern.compile("\\[engine:([^\\]]+)]");

    /// Builds the metadata recorded once in `test_run`.
    TestRunContext collectRunContext(Path workingDirectory) {
        Map<String, String> environment = System.getenv();
        BrowserSettings browserSettings = collectBrowserSettings();
        String ciProvider = detectCiProvider(environment);
        return new TestRunContext(
                UUID.randomUUID().toString(),
                "junit5",
                "gradle-test",
                Instant.now(),
                ciProvider == null ? "local" : ciProvider,
                blankToNull(System.getProperty(TestStatisticsDataSourceFactory.RUN_LABEL_PROPERTY)),
                workingDirectory.toString(),
                firstNonBlank(
                        environment.get("GITHUB_SHA"),
                        environment.get("CI_COMMIT_SHA"),
                        environment.get("BUILD_SOURCEVERSION"),
                        gitValue(workingDirectory, "rev-parse", "HEAD")),
                firstNonBlank(
                        environment.get("GITHUB_REF_NAME"),
                        environment.get("CI_COMMIT_REF_NAME"),
                        environment.get("BUILD_SOURCEBRANCHNAME"),
                        environment.get("BRANCH_NAME"),
                        gitValue(workingDirectory, "rev-parse", "--abbrev-ref", "HEAD")),
                ciProvider,
                firstNonBlank(
                        environment.get("GITHUB_RUN_ID"),
                        environment.get("CI_PIPELINE_ID"),
                        environment.get("BUILD_BUILDID"),
                        environment.get("BUILD_ID"),
                        environment.get("CIRCLE_WORKFLOW_ID")),
                firstNonBlank(
                        environment.get("GITHUB_JOB"),
                        environment.get("CI_JOB_NAME"),
                        environment.get("JOB_NAME"),
                        environment.get("AGENT_JOBNAME")),
                hostName(),
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                System.getProperty("java.vendor"),
                System.getProperty("java.version"),
                browserSettings.browserName(),
                browserSettings.browserChannel(),
                browserSettings.headless());
    }

    /// Builds the stable logical identity recorded in `test_case`.
    TestCaseDetails collectTestCaseDetails(ExtensionContext context) {
        String uniqueId = context.getUniqueId();
        Class<?> testClass = context.getTestClass().orElse(null);
        return new TestCaseDetails(
                sha256Hex("junit5|" + uniqueId),
                "junit5",
                engineId(uniqueId),
                uniqueId,
                testClass == null ? null : blankToNull(testClass.getPackageName()),
                testClass == null ? null : testClass.getName(),
                context.getTestMethod().map(method -> method.getName()).orElse(null),
                context.getDisplayName(),
                new TreeSet<>(context.getTags()));
    }

    private BrowserSettings collectBrowserSettings() {
        String browserName = blankToNull(System.getProperty("playwright.browser"));
        String channel = blankToNull(System.getProperty("playwright.channel"));
        Integer headless = parseHeadless(System.getProperty("playwright.headless"));

        if (browserName != null && channel != null && headless != null) {
            return new BrowserSettings(browserName, channel, headless);
        }

        try {
            Config playwrightConfig = ConfigFactory.load("playwright").getConfig("playwright");
            if (browserName == null && playwrightConfig.hasPath("browser")) {
                browserName = blankToNull(playwrightConfig.getString("browser"));
            }
            if (channel == null && playwrightConfig.hasPath("channel")) {
                channel = blankToNull(playwrightConfig.getString("channel"));
            }
            if (headless == null && playwrightConfig.hasPath("headless")) {
                headless = playwrightConfig.getBoolean("headless") ? 1 : 0;
            }
        } catch (ConfigException ignored) {
            // Playwright config is optional for non-browser tests.
        }

        return new BrowserSettings(browserName, channel, headless);
    }

    private Integer parseHeadless(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(value) ? 1 : 0;
    }

    private String detectCiProvider(Map<String, String> environment) {
        if (environment.containsKey("GITHUB_ACTIONS")) {
            return "github-actions";
        }
        if (environment.containsKey("GITLAB_CI")) {
            return "gitlab-ci";
        }
        if (environment.containsKey("JENKINS_URL")) {
            return "jenkins";
        }
        if (environment.containsKey("BUILD_BUILDID")) {
            return "azure-pipelines";
        }
        if (environment.containsKey("CIRCLECI")) {
            return "circleci";
        }
        if (environment.containsKey("CI")) {
            return "generic-ci";
        }
        return null;
    }

    private String gitValue(Path workingDirectory, String... args) {
        if (!Files.exists(workingDirectory.resolve(".git"))) {
            return null;
        }
        ProcessBuilder builder = new ProcessBuilder();
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        builder.command(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String value = reader.readLine();
                int exitCode = process.waitFor();
                if (exitCode == 0 && value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return null;
        }
    }

    private String engineId(String uniqueId) {
        Matcher matcher = ENGINE_ID_PATTERN.matcher(uniqueId);
        return matcher.find() ? matcher.group(1) : "junit-jupiter";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    /// Effective Playwright browser settings attached to a `test_run`.
    record BrowserSettings(String browserName, String browserChannel, Integer headless) {}

    /// Run-level dimensions stored once per launcher session.
    record TestRunContext(
            String runId,
            String framework,
            String runner,
            Instant startedAt,
            String triggerSource,
            String runLabel,
            String workingDirectory,
            String gitCommit,
            String gitBranch,
            String ciProvider,
            String ciBuildId,
            String ciJobName,
            String hostName,
            String osName,
            String osArch,
            String jvmVendor,
            String jvmVersion,
            String browserName,
            String browserChannel,
            Integer headless) {}

    /// Stable logical test identity and tags stored in `test_case` and `test_case_tag`.
    record TestCaseDetails(
            String testCaseId,
            String framework,
            String engineId,
            String uniqueId,
            String packageName,
            String className,
            String methodName,
            String displayName,
            Set<String> tags) {}
}
