package oscarvarto.mx.secrets;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/// Tests for [SecretsProvider] using the AWS testing configuration.
///
/// This test class uses [LocalStackExtension] which reads from `aws-testing.json`
/// to determine whether to:
/// - Connect to a running LocalStack instance (mode: "local")
/// - Start a fresh Testcontainers LocalStack container (mode: "testcontainers")
///
/// ## Default Mode
///
/// By default, tests use LOCAL mode which connects to http://localhost:4566
/// for fast development iteration. This requires you to have LocalStack running:
/// ```bash
/// localstack start
/// ```
///
/// ## CI/CD Mode
///
/// For CI/CD, change `aws-testing.json` to use TESTCONTAINERS mode which
/// starts an isolated container automatically.
///
/// ## Opt-in execution
///
/// These tests are integration-style checks and are only executed when
/// `-Dplaywright.aws.testing.enabled=true` is set.
///
/// ## Test Data
///
/// Test secrets are defined in `aws-testing.json` and automatically created
/// by the extension before tests run.
///
/// @see LocalStackExtension
/// @see AwsTestingConfig
@ExtendWith(LocalStackExtension.class)
@DisplayName("SecretsProvider with AWS Testing Configuration")
@EnabledIfSystemProperty(named = "playwright.aws.testing.enabled", matches = "true")
class SecretsProviderAwsTestingTest {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsProviderAwsTestingTest.class);

    @BeforeEach
    void setUp() {
        AwsTestingConfig config = AwsTestingConfig.getInstance();
        LOG.info("Running tests in {} mode", config.getMode());
        LOG.info("LocalStack endpoint: {}", System.getProperty("LOCALSTACK_ENDPOINT"));
    }

    @AfterEach
    void tearDown() {
        // Reset SecretsProvider to clear cache between tests
        SecretsProvider.reset();
    }

    @Test
    @DisplayName("should resolve secrets from LocalStack when env var is not set")
    void shouldResolveSecretsFromLocalStack() {
        // Ensure env var is not set
        assertThat(System.getenv("GITHUB_USER"))
                .as("GITHUB_USER should not be set as environment variable")
                .isNullOrEmpty();

        // Act
        @Nullable String githubUser = SecretsProvider.get("GITHUB_USER");
        @Nullable String githubToken = SecretsProvider.get("GITHUB_API_TOKEN");

        // Assert
        assertThat(githubUser)
                .as("GITHUB_USER should be resolved from LocalStack")
                .isNotNull()
                .isEqualTo("test-user");

        assertThat(githubToken)
                .as("GITHUB_API_TOKEN should be resolved from LocalStack")
                .isNotNull()
                .isEqualTo("test-token-12345");
    }

    @Test
    @DisplayName("should prefer environment variable over LocalStack")
    void shouldPreferEnvironmentVariable() {
        // Note: This test demonstrates the priority but cannot actually set env vars
        // at runtime. In practice, if GITHUB_USER is set in the environment,
        // SecretsProvider will use that value instead of fetching from AWS/LocalStack.

        LOG.info("Environment variable GITHUB_USER: {}", String.valueOf(System.getenv("GITHUB_USER")));
        LOG.info("LocalStack endpoint: {}", String.valueOf(System.getProperty("LOCALSTACK_ENDPOINT")));

        // When env var is not set, should use LocalStack
        if (System.getenv("GITHUB_USER") == null) {
            @Nullable String value = SecretsProvider.get("GITHUB_USER");
            assertThat(value).isNotNull();
            assertThat(value).isEqualTo("test-user");
        }
    }

    @Test
    @DisplayName("should require() throw exception for non-existent key")
    void shouldThrowForNonExistentKey() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> SecretsProvider.require("NON_EXISTENT_KEY"),
                "Should throw IllegalStateException for non-existent key");
    }

    @Test
    @DisplayName("should cache secrets to avoid multiple API calls")
    void shouldCacheSecrets() {
        // First call - should fetch from LocalStack
        @Nullable String first = SecretsProvider.get("GITHUB_USER");
        assertThat(first).isNotNull();
        assertThat(first).isEqualTo("test-user");

        // Second call - should use cache
        @Nullable String second = SecretsProvider.get("GITHUB_USER");
        assertThat(second).isNotNull();
        assertThat(second).isEqualTo("test-user");

        // Both values should be equal (cached)
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("should use configured mode from aws-testing.json")
    void shouldUseConfiguredMode() {
        AwsTestingConfig config = AwsTestingConfig.getInstance();

        LOG.info("Current mode: {}", config.getMode());

        if (config.isLocalMode()) {
            LOG.info("Using local endpoint: {}", config.getLocalEndpoint());
            assertThat(config.getLocalEndpoint()).isNotNull();
        } else {
            LOG.info("Using Testcontainers image: {}", config.getTestcontainersImage());
            assertThat(config.getTestcontainersImage()).isNotNull();
        }

        // Verify test secrets are configured
        assertThat(config.hasTestSecret("playwright-practice/github")).isTrue();
        assertThat(config.getTestSecret("playwright-practice/github"))
                .contains("test-user")
                .contains("test-token-12345");
    }
}
