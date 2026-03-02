package oscarvarto.mx.secrets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/// Static utility for resolving test secrets with an **env-var-first** strategy.
///
/// Resolution order for each logical key:
///
/// 1. **Environment variable** &mdash; `System.getenv(key)`. If present, the value
///    is returned immediately and no AWS call is made.
/// 2. **AWS Secrets Manager** &mdash; looked up via the mapping defined in
///    `secrets.json` on the test classpath. Supports both plain-string and
///    JSON-format secrets (use `field` in the mapping to extract a single JSON key).
///
/// Fetched AWS secrets are cached in-memory (keyed by AWS secret name) so that
/// multiple logical keys pointing to the same AWS secret trigger only one API call.
///
/// The [SecretsManagerClient] is created lazily with double-checked locking and is
/// never instantiated if all requested secrets are satisfied by environment variables.
///
/// ## Configuration
///
/// Place a `secrets.json` file on the test classpath (typically
/// `src/test/resources/secrets.json`) with the following structure:
///
/// ```json
/// {
///   "secrets": {
///     "region": "us-east-1",
///     "mappings": {
///       "LOGICAL_KEY": {
///         "secretName": "aws/secret/name-or-arn",
///         "field": "optionalJsonField"
///       }
///     }
///   }
/// }
/// ```
///
/// @see #get(String)
/// @see #require(String)
public final class SecretsProvider {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsProvider.class);
    private static final Config SECRETS_CONFIG = ConfigFactory.load("secrets").getConfig("secrets");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /// Cache of raw AWS secret values, keyed by AWS secret name.
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    /// Guard lock for lazy [SecretsManagerClient] initialization.
    private static final Object CLIENT_LOCK = new Object();

    private static volatile @Nullable SecretsManagerClient client;

    private SecretsProvider() {}

    /// Resolves a logical secret key to its value.
    ///
    /// Checks the environment first, then falls back to AWS Secrets Manager
    /// using the mapping in `secrets.json`. Returns `null` if the key is
    /// not found in either source.
    ///
    /// @param key the logical key (e.g. `"GITHUB_USER"`)
    /// @return the resolved secret value, or `null` if not found
    public static @Nullable String get(String key) {
        String envValue = System.getenv(key);
        if (envValue != null) {
            LOG.debug("Resolved '{}' from environment variable", key);
            return envValue;
        }

        Config mappings = SECRETS_CONFIG.getConfig("mappings");
        if (!mappings.hasPath(key)) {
            LOG.warn("No mapping found for key '{}' in secrets.json", key);
            return null;
        }

        Config mapping = mappings.getConfig(key);
        String secretName = mapping.getString("secretName");
        String rawSecret = CACHE.computeIfAbsent(secretName, SecretsProvider::fetchSecret);

        if (mapping.hasPath("field")) {
            String field = mapping.getString("field");
            String extracted = extractField(rawSecret, field);
            LOG.info("Resolved '{}' from AWS secret '{}' field '{}'", key, secretName, field);
            return extracted;
        }

        LOG.info("Resolved '{}' from AWS secret '{}'", key, secretName);
        return rawSecret;
    }

    /// Resolves a logical secret key to its value, throwing if absent.
    ///
    /// Behaves identically to [#get(String)] but raises an
    /// [IllegalStateException] when the key cannot be resolved from either
    /// environment variables or AWS Secrets Manager.
    ///
    /// @param key the logical key (e.g. `"GITHUB_API_TOKEN"`)
    /// @return the resolved secret value (never `null`)
    /// @throws IllegalStateException if the key is not found
    public static String require(String key) {
        String value = get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "Secret '%s' not found in environment variables or AWS Secrets Manager".formatted(key));
        }
        return value;
    }

    /// Fetches a secret value from AWS Secrets Manager.
    private static String fetchSecret(String secretName) {
        LOG.info("Fetching secret '{}' from AWS Secrets Manager", secretName);
        return getClient()
                .getSecretValue(
                        GetSecretValueRequest.builder().secretId(secretName).build())
                .secretString();
    }

    /// Extracts a field from a JSON-formatted secret string.
    private static @Nullable String extractField(String json, String field) {
        try {
            JsonNode node = MAPPER.readTree(json);
            JsonNode fieldNode = node.get(field);
            return fieldNode != null ? fieldNode.asText() : null;
        } catch (Exception e) {
            LOG.warn("Failed to parse secret as JSON for field '{}': {}", field, e.getMessage());
            return null;
        }
    }

    /// Returns the lazily-initialized [SecretsManagerClient], creating it on first use
    /// with double-checked locking.
    ///
    /// Supports LocalStack via the LOCALSTACK_ENDPOINT environment variable or system property.
    private static SecretsManagerClient getClient() {
        SecretsManagerClient localRef = client;
        if (localRef == null) {
            synchronized (CLIENT_LOCK) {
                localRef = client;
                if (localRef == null) {
                    String region = SECRETS_CONFIG.getString("region");
                    // Check both environment variable and system property
                    String localstackEndpoint = System.getenv("LOCALSTACK_ENDPOINT");
                    if (localstackEndpoint == null || localstackEndpoint.isEmpty()) {
                        localstackEndpoint = System.getProperty("LOCALSTACK_ENDPOINT");
                    }
                    LOG.info("Creating SecretsManagerClient for region '{}'", region);

                    var builder = SecretsManagerClient.builder().region(Region.of(region));

                    if (localstackEndpoint != null && !localstackEndpoint.isEmpty()) {
                        LOG.info("Using LocalStack endpoint: {}", localstackEndpoint);
                        builder.endpointOverride(URI.create(localstackEndpoint))
                                .credentialsProvider(
                                        StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
                    }

                    localRef = builder.build();
                    client = localRef;
                }
            }
        }
        return localRef;
    }

    /// Resets the cached client and clears the secret cache.
    /// This is primarily used for testing to ensure a clean state
    /// between tests (especially when switching between real AWS and LocalStack).
    public static void reset() {
        synchronized (CLIENT_LOCK) {
            if (client != null) {
                client.close();
                client = null;
            }
            CACHE.clear();
            LOG.info("SecretsProvider has been reset");
        }
    }
}
