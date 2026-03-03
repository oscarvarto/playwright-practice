package oscarvarto.mx.aws;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.List;
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
///     "services": ["secretsmanager"],
///     "local": {
///       "endpoint": "http://localhost:4566",
///       "region": "us-east-1"
///     },
///     "testcontainers": {
///       "image": "localstack/localstack:4.0",
///       "region": "us-east-1"
///     },
///     "secretsmanager": {
///       "secrets": {
///         "playwright-practice/github": {
///           "username": "test-user",
///           "token": "test-token-12345"
///         }
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
///
/// // Get the list of services to provision
/// List<String> services = config.getServices();
///
/// // Get service-specific config
/// Config smConfig = config.getServiceConfig("secretsmanager");
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

    /// Returns the list of AWS service names to provision.
    ///
    /// Reads the `"services"` array from `aws-testing.json`.
    ///
    /// @return list of service names (e.g. `["secretsmanager"]`)
    public List<String> getServices() {
        return config.getStringList("services");
    }

    /// Returns the service-specific configuration block.
    ///
    /// For example, `getServiceConfig("secretsmanager")` returns the
    /// `"secretsmanager"` sub-object from `aws-testing.json`, which contains
    /// the `"secrets"` definitions for that service.
    ///
    /// @param serviceName the service name (e.g. `"secretsmanager"`)
    /// @return the service-specific config, or an empty config if not present
    public Config getServiceConfig(String serviceName) {
        if (config.hasPath(serviceName)) {
            return config.getConfig(serviceName);
        }
        return ConfigFactory.empty();
    }
}
