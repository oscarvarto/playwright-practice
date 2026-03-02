package oscarvarto.mx.secrets;

import java.net.URI;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;

/// JUnit 5 extension that manages AWS testing infrastructure.
///
/// This extension reads configuration from `aws-testing.json` and supports two modes:
///
/// 1. **LOCAL mode** (default): Connects to an already running LocalStack instance
///    at http://localhost:4566 (or configured endpoint). Fast for development.
///
/// 2. **TESTCONTAINERS mode**: Starts a fresh LocalStack Docker container using
///    Testcontainers. Provides isolation for CI/CD.
///
/// ## Configuration
///
/// Set mode in `src/test/resources/aws-testing.json`:
/// ```json
/// {
///   "aws-testing": {
///     "mode": "local"
///   }
/// }
/// ```
///
/// ## Usage
///
/// ```java
/// @ExtendWith(LocalStackExtension.class)
/// class MyTest {
///     @Test
///     void test() {
///         // Extension automatically configured SecretsProvider
///         String secret = SecretsProvider.get("GITHUB_USER");
///     }
/// }
/// ```
///
/// @see AwsTestingConfig
/// @see SecretsProvider
public class LocalStackExtension implements BeforeAllCallback, AfterAllCallback {

    private static final Logger LOG = LoggerFactory.getLogger(LocalStackExtension.class);
    private static final String ENDPOINT_KEY = "localstack.endpoint";
    private static final String MODE_KEY = "localstack.mode";

    private static LocalStackContainer localstack;

    @Override
    public void beforeAll(ExtensionContext context) {
        ExtensionContext.Store store = getStore(context);

        // Only initialize once per test class hierarchy
        if (store.get(ENDPOINT_KEY) != null) {
            return;
        }

        AwsTestingConfig config = AwsTestingConfig.getInstance();
        store.put(MODE_KEY, config.getMode().name());

        if (config.isLocalMode()) {
            LOG.info("Using LOCAL mode - connecting to running LocalStack instance");
            setupLocalMode(config, store);
        } else {
            LOG.info("Using TESTCONTAINERS mode - starting LocalStack container");
            setupTestcontainersMode(config, store);
        }
    }

    private void setupLocalMode(AwsTestingConfig config, ExtensionContext.Store store) {
        String endpoint = config.getLocalEndpoint();
        String region = config.getLocalRegion();

        LOG.info("Configuring SecretsProvider for LocalStack at: {} (region: {})", endpoint, region);

        // Set system property for SecretsProvider to use LocalStack
        System.setProperty("LOCALSTACK_ENDPOINT", endpoint);
        System.setProperty("LOCALSTACK_REGION", region);

        store.put(ENDPOINT_KEY, endpoint);

        // Create test secrets in the running LocalStack
        createTestSecretsInLocalStack(endpoint, region, config);
    }

    private void setupTestcontainersMode(AwsTestingConfig config, ExtensionContext.Store store) {
        String image = config.getTestcontainersImage();
        String region = config.getTestcontainersRegion();

        LOG.info("Starting LocalStack container with image: {}", image);

        localstack = new LocalStackContainer(DockerImageName.parse(image))
                .withServices(LocalStackContainer.Service.SECRETSMANAGER);

        localstack.start();

        String endpoint = localstack
                .getEndpointOverride(LocalStackContainer.Service.SECRETSMANAGER)
                .toString();

        LOG.info("LocalStack container started at: {} (region: {})", endpoint, region);

        // Set system property for SecretsProvider
        System.setProperty("LOCALSTACK_ENDPOINT", endpoint);
        System.setProperty("LOCALSTACK_REGION", region);

        store.put(ENDPOINT_KEY, endpoint);

        // Create test secrets in the container
        createTestSecretsInLocalStack(endpoint, region, config);
    }

    private void createTestSecretsInLocalStack(String endpoint, String region, AwsTestingConfig config) {
        LOG.info("Creating test secrets in LocalStack...");

        SecretsManagerClient client = SecretsManagerClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();

        try {
            for (var entry : config.getTestSecrets().entrySet()) {
                String secretName = entry.getKey();
                String secretValue = entry.getValue();

                try {
                    client.createSecret(CreateSecretRequest.builder()
                            .name(secretName)
                            .secretString(secretValue)
                            .build());
                    LOG.info("Created test secret: {}", secretName);
                } catch (ResourceExistsException e) {
                    LOG.info("Test secret already exists: {}", secretName);
                }
            }
        } finally {
            client.close();
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ExtensionContext.Store store = getStore(context);

        // Only cleanup if we're the root of the hierarchy
        if (context.getParent().isPresent()
                && context.getParent().get().getTestClass().isEmpty()) {

            String mode = store.get(MODE_KEY, String.class);

            if ("TESTCONTAINERS".equals(mode) && localstack != null) {
                LOG.info("Stopping LocalStack container...");
                localstack.stop();
                localstack = null;
            }

            System.clearProperty("LOCALSTACK_ENDPOINT");
            System.clearProperty("LOCALSTACK_REGION");
            LOG.info("Cleaned up LocalStack configuration");
        }
    }

    /// Gets the LocalStack endpoint URL.
    ///
    /// @param context the extension context
    /// @return the endpoint URL, or null if not initialized
    public static String getEndpoint(ExtensionContext context) {
        return getStore(context).get(ENDPOINT_KEY, String.class);
    }

    /// Gets the LocalStack container instance (only available in testcontainers mode).
    ///
    /// @return the container instance, or null if not started or in local mode
    public static LocalStackContainer getContainer() {
        return localstack;
    }

    private static ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(ExtensionContext.Namespace.create(LocalStackExtension.class.getName()));
    }
}
