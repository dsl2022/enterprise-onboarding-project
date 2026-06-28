package com.eop.wif;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * AWS clients for the issuer signer/publisher. Created only when {@code wif.enabled=true}, so the
 * default-credentials lookup never happens during a plain Phase 1 boot. On ECS the task role supplies
 * credentials and {@code AWS_REGION} is set; locally you'd set {@code wif.region}.
 */
@Configuration
@ConditionalOnProperty(prefix = "wif", name = "enabled", havingValue = "true")
public class AwsClientsConfig {

    private Region region(IssuerProperties props) {
        return StringUtils.hasText(props.getRegion()) ? Region.of(props.getRegion()) : null;
    }

    @Bean
    SecretsManagerClient secretsManagerClient(IssuerProperties props) {
        var b = SecretsManagerClient.builder();
        Region r = region(props);
        if (r != null) {
            b.region(r);
        }
        return b.build();
    }

    @Bean
    S3Client s3Client(IssuerProperties props) {
        var b = S3Client.builder();
        Region r = region(props);
        if (r != null) {
            b.region(r);
        }
        return b.build();
    }
}
