package oscarvarto.mx.aws;

import com.typesafe.config.Config;
import java.util.List;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

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
/// Services to start and provision are driven by the `"services"` array in the config.
/// Each service is resolved to a [ServiceProvisioner] that sets up test fixtures.
///
/// ## Configuration
///
/// Set mode and services in `src/test/resources/aws-testing.json`:
/// ```json
/// {
///   "aws-testing": {
///     "mode": "local",
///     "services": ["secretsmanager"]
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
///         // Extension automatically provisioned services
///         String secret = SecretsProvider.get("GITHUB_USER");
///     }
/// }
/// ```
///
/// @see AwsTestingConfig
/// @see ServiceProvisioner
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

        LOG.info("Configuring for LocalStack at: {} (region: {})", endpoint, region);

        // Set system property for SecretsProvider to use LocalStack
        System.setProperty("LOCALSTACK_ENDPOINT", endpoint);

        store.put(ENDPOINT_KEY, endpoint);

        // Provision all configured services
        provisionServices(config, endpoint, region);
    }

    private void setupTestcontainersMode(AwsTestingConfig config, ExtensionContext.Store store) {
        String image = config.getTestcontainersImage();
        String region = config.getTestcontainersRegion();

        LOG.info("Starting LocalStack container with image: {}", image);

        List<String> serviceNames = config.getServices();
        LocalStackContainer.Service[] services = serviceNames.stream()
                .map(ServiceProvisioner::forService)
                .filter(Objects::nonNull)
                .map(ServiceProvisioner::service)
                .toArray(LocalStackContainer.Service[]::new);

        localstack = new LocalStackContainer(DockerImageName.parse(image)).withServices(services);

        localstack.start();

        String endpoint = localstack.getEndpoint().toString();

        LOG.info("LocalStack container started at: {} (region: {})", endpoint, region);

        // Set system property for SecretsProvider
        System.setProperty("LOCALSTACK_ENDPOINT", endpoint);

        store.put(ENDPOINT_KEY, endpoint);

        // Provision all configured services
        provisionServices(config, endpoint, region);
    }

    private void provisionServices(AwsTestingConfig config, String endpoint, String region) {
        for (String name : config.getServices()) {
            ServiceProvisioner provisioner = ServiceProvisioner.forService(name);
            if (provisioner != null) {
                Config svcConfig = config.getServiceConfig(name);
                provisioner.provision(endpoint, region, svcConfig);
            } else {
                LOG.warn("No provisioner found for service: {}", name);
            }
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
            LOG.info("Cleaned up LocalStack configuration");
        }
    }

    /// Gets the LocalStack endpoint URL.
    ///
    /// @param context the extension context
    /// @return the endpoint URL, or null if not initialized
    public static @Nullable String getEndpoint(ExtensionContext context) {
        return getStore(context).get(ENDPOINT_KEY, String.class);
    }

    /// Gets the LocalStack container instance (only available in testcontainers mode).
    ///
    /// @return the container instance, or null if not started or in local mode
    public static @Nullable LocalStackContainer getContainer() {
        return localstack;
    }

    private static ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(ExtensionContext.Namespace.create(LocalStackExtension.class.getName()));
    }
}
