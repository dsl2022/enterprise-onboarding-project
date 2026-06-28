package com.eop.wif;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Workload-identity-federation config. All values come from the environment (set on the ECS task in
 * Phase 4); nothing is committed. When {@code wif.enabled} is false (Phase 1 local/CI), none of the
 * issuer beans are created, so the app boots with no AWS dependency.
 */
@ConfigurationProperties(prefix = "wif")
public class IssuerProperties {

    /** Master switch for the issuer/WIF beans. */
    private boolean enabled = false;

    /** Public issuer host, e.g. https://dxxxx.cloudfront.net (must equal the discovery doc's `issuer`). */
    private String issuerHost;

    /** S3 bucket that CloudFront serves; the app PUTs .well-known/jwks.json here. */
    private String issuerBucket;

    /** Secrets Manager name/ARN holding the RSA private key (JWK JSON). */
    private String signingSecretName;

    /** Subject claim used in the workload assertion; must match the Entra federated credential (Phase 3/5). */
    private String subject;

    /** AWS region override; falls back to the default provider chain (AWS_REGION) when blank. */
    private String region;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuerHost() {
        return issuerHost;
    }

    public void setIssuerHost(String issuerHost) {
        this.issuerHost = issuerHost;
    }

    public String getIssuerBucket() {
        return issuerBucket;
    }

    public void setIssuerBucket(String issuerBucket) {
        this.issuerBucket = issuerBucket;
    }

    public String getSigningSecretName() {
        return signingSecretName;
    }

    public void setSigningSecretName(String signingSecretName) {
        this.signingSecretName = signingSecretName;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }
}
