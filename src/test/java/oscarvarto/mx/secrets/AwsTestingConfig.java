package oscarvarto.mx.secrets;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Configuration for AWS testing environments.
///
/// Reads from `aws-testing.json` on the test classpath and provides settings
/// for both LocalStack modes:
/// - **local**: Connects to a running LocalStack instance (fast, for development)
/// - **testcontainers**: Starts a fresh LocalStack container (isolated, for CI/CD)
///
/// ## Configuration File Structure
///
/// ```json
/// {
///   "aws-testing": {
///     "mode": "local",
///     "local": {
///       "endpoint": "http://localhost:4566",
///       "region": "us-east-1"
///     },
///     "testcontainers": {
///       "image": "localstack/localstack:4.0",
///       "region": "us-east-1"
///     },
///     "secrets": {
///       "playwright-practice/github": {
///         "username": "test-user",
///         "token": "test-token-12345"
///       }
///     }
///   }
/// }
/// ```
///
/// ## Usage
///
/// ```java
/// AwsTestingConfig config = AwsTestingConfig.getInstance();
///
/// if (config.isLocalMode()) {
///     String endpoint = config.getLocalEndpoint();
/// } else {
///     String image = config.getTestcontainersImage();
/// }
/// ```
///
/// @see #getInstance()
/// @see #isLocalMode()
/// @see #isTestcontainersMode()
public class AwsTestingConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AwsTestingConfig.class);
    private static final String CONFIG_NAME = "aws-testing";
    private static final String ROOT_PATH = "aws-testing";

    private static volatile AwsTestingConfig instance;

    private final Config config;
    private final Mode mode;

    /// The testing mode for AWS services.
    public enum Mode {
        /// Uses a locally running LocalStack instance (fast, development)
        LOCAL,
        /// Starts a fresh Testcontainers LocalStack container (isolated, CI/CD)
        TESTCONTAINERS
    }

    private AwsTestingConfig() {
        this.config = ConfigFactory.load(CONFIG_NAME).getConfig(ROOT_PATH);
        this.mode = Mode.valueOf(config.getString("mode").toUpperCase());
        LOG.info("AWS Testing configured with mode: {}", mode);
    }

    /// Returns the singleton instance of the configuration.
    public static AwsTestingConfig getInstance() {
        if (instance == null) {
            synchronized (AwsTestingConfig.class) {
                if (instance == null) {
                    instance = new AwsTestingConfig();
                }
            }
        }
        return instance;
    }

    /// Resets the singleton instance (useful for testing).
    public static void reset() {
        synchronized (AwsTestingConfig.class) {
            instance = null;
            LOG.info("AwsTestingConfig has been reset");
        }
    }

    /// Returns the configured mode (LOCAL or TESTCONTAINERS).
    public Mode getMode() {
        return mode;
    }

    /// Returns true if running in local mode.
    public boolean isLocalMode() {
        return mode == Mode.LOCAL;
    }

    /// Returns true if running in testcontainers mode.
    public boolean isTestcontainersMode() {
        return mode == Mode.TESTCONTAINERS;
    }

    /// Returns the endpoint URL for local mode.
    ///
    /// @throws IllegalStateException if not in local mode
    public String getLocalEndpoint() {
        if (!isLocalMode()) {
            throw new IllegalStateException("Not in local mode. Current mode: " + mode);
        }
        return config.getString("local.endpoint");
    }

    /// Returns the region for local mode.
    ///
    /// @throws IllegalStateException if not in local mode
    public String getLocalRegion() {
        if (!isLocalMode()) {
            throw new IllegalStateException("Not in local mode. Current mode: " + mode);
        }
        return config.getString("local.region");
    }

    /// Returns the Docker image for testcontainers mode.
    ///
    /// @throws IllegalStateException if not in testcontainers mode
    public String getTestcontainersImage() {
        if (!isTestcontainersMode()) {
            throw new IllegalStateException("Not in testcontainers mode. Current mode: " + mode);
        }
        return config.getString("testcontainers.image");
    }

    /// Returns the region for testcontainers mode.
    ///
    /// @throws IllegalStateException if not in testcontainers mode
    public String getTestcontainersRegion() {
        if (!isTestcontainersMode()) {
            throw new IllegalStateException("Not in testcontainers mode. Current mode: " + mode);
        }
        return config.getString("testcontainers.region");
    }

    /// Returns all configured test secrets.
    ///
    /// @return map of secret names to their JSON content
    public Map<String, String> getTestSecrets() {
        Map<String, String> secrets = new HashMap<>();

        if (!config.hasPath("secrets")) {
            LOG.warn("No test secrets configured in aws-testing.json");
            return secrets;
        }

        Config secretsConfig = config.getConfig("secrets");
        Set<String> secretNames = secretsConfig.root().keySet();

        for (String secretName : secretNames) {
            Config secretConfig = secretsConfig.getConfig(secretName);
            StringBuilder jsonBuilder = new StringBuilder();
            jsonBuilder.append("{");

            Set<String> fields = secretConfig.root().keySet();
            boolean first = true;
            for (String field : fields) {
                if (!first) {
                    jsonBuilder.append(",");
                }
                first = false;
                String value = secretConfig.getString(field);
                jsonBuilder
                        .append("\"")
                        .append(field)
                        .append("\":\"")
                        .append(value)
                        .append("\"");
            }

            jsonBuilder.append("}");
            String json = jsonBuilder.toString();
            secrets.put(secretName, json);
            LOG.debug("Configured test secret: {} = {}", secretName, json);
        }

        return secrets;
    }

    /// Returns the JSON content for a specific test secret.
    ///
    /// @param secretName the name of the secret
    /// @return the JSON content, or null if not found
    public String getTestSecret(String secretName) {
        return getTestSecrets().get(secretName);
    }

    /// Returns true if the specified secret is configured.
    public boolean hasTestSecret(String secretName) {
        return getTestSecrets().containsKey(secretName);
    }
}
