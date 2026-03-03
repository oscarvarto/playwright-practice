package oscarvarto.mx.aws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import java.net.URI;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;

/// Provisioner for AWS Secrets Manager test fixtures.
///
/// Reads the `"secrets"` block from the `"secretsmanager"` service config
/// in `aws-testing.json` and creates each secret in LocalStack.
public class SecretsManagerProvisioner implements ServiceProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsManagerProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public LocalStackContainer.Service service() {
        return LocalStackContainer.Service.SECRETSMANAGER;
    }

    @Override
    public void provision(String endpoint, String region, Config serviceConfig) {
        if (!serviceConfig.hasPath("secrets")) {
            LOG.warn("No secrets configured in secretsmanager service config");
            return;
        }

        LOG.info("Creating test secrets in LocalStack...");

        Config secretsConfig = serviceConfig.getConfig("secrets");
        Set<String> secretNames = secretsConfig.root().keySet();

        SecretsManagerClient client = SecretsManagerClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();

        try {
            for (String secretName : secretNames) {
                Config secretConfig = secretsConfig.getConfig(secretName);
                ObjectNode node = MAPPER.createObjectNode();
                for (String field : secretConfig.root().keySet()) {
                    node.put(field, secretConfig.getString(field));
                }
                String secretValue = node.toString();

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
}
