package oscarvarto.mx.aws;

import com.typesafe.config.Config;
import org.testcontainers.containers.localstack.LocalStackContainer;

/// Strategy interface for provisioning a specific AWS service in LocalStack.
///
/// Implementations set up test fixtures (secrets, buckets, queues, etc.)
/// after LocalStack is available. [LocalStackExtension] discovers provisioners
/// via [#forService(String)] and invokes [#provision] for each service
/// listed in `aws-testing.json`.
public interface ServiceProvisioner {

    /// The LocalStack service this provisioner manages.
    LocalStackContainer.Service service();

    /// Provisions test fixtures for this service.
    ///
    /// @param endpoint      the LocalStack endpoint URL
    /// @param region        the AWS region
    /// @param serviceConfig the service-specific config block from `aws-testing.json`
    ///                      (e.g. the `"secretsmanager"` sub-object), or absent if none
    void provision(String endpoint, String region, Config serviceConfig);

    /// Registry lookup. Returns the provisioner for the given service name,
    /// or null if no provisioner is registered.
    static ServiceProvisioner forService(String serviceName) {
        return switch (serviceName) {
            case "secretsmanager" -> new SecretsManagerProvisioner();
            default -> null;
        };
    }
}
