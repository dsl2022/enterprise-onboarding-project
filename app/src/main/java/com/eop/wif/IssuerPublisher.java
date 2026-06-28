package com.eop.wif;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * On startup, publishes the public JWKS to the issuer bucket at {@code .well-known/jwks.json}.
 * CloudFront then serves it over HTTPS at {@code <issuer-host>/.well-known/jwks.json}, which is the
 * {@code jwks_uri} advertised by the Terraform-published discovery document. Entra fetches it during
 * the Phase 5 token exchange to validate the workload assertion. No key material is logged.
 */
@Component
@ConditionalOnProperty(prefix = "wif", name = "enabled", havingValue = "true")
public class IssuerPublisher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IssuerPublisher.class);
    private static final String JWKS_KEY = ".well-known/jwks.json";

    private final S3Client s3;
    private final IssuerKeyService keyService;
    private final IssuerProperties props;

    public IssuerPublisher(S3Client s3, IssuerKeyService keyService, IssuerProperties props) {
        this.s3 = s3;
        this.keyService = keyService;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String jwks = keyService.publicJwkSet().toString(); // public keys only
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(props.getIssuerBucket())
                        .key(JWKS_KEY)
                        .contentType("application/json")
                        .build(),
                RequestBody.fromString(jwks));
        log.info("Published JWKS to s3://{}/{} (kid={}); served at {}/{}",
                props.getIssuerBucket(), JWKS_KEY, keyService.signingKey().getKeyID(),
                props.getIssuerHost(), JWKS_KEY);
    }
}
