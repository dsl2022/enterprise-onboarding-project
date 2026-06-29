package com.eop.wif;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

/**
 * Owns the issuer's RSA signing key. The key lives ONLY in AWS Secrets Manager — never in Terraform
 * state or the repo. On first use the app generates it and writes the first secret version; thereafter
 * it loads the existing key. The {@code kid} is the RFC-7638 JWK thumbprint, so the public key in
 * {@code jwks.json} and the {@code kid} header of every minted assertion (Phase 5) always agree.
 *
 * <p><b>Single-writer under HA (Phase 8, #77).</b> With ≥2 tasks, a naive cold-start would have each task
 * generate its OWN key and {@code putSecretValue} (last-write-wins) + publish its own JWKS (overwriting the
 * single {@code jwks.json}) — so the loser's assertions carry a {@code kid} not in the published JWKS and
 * fail at Entra, breaking app→Graph provisioning intermittently. We close that by holding a Postgres
 * <b>advisory lock</b> (the same primitive as the audit relay) around the get-or-generate: exactly one task
 * generates+stores; the others block, then LOAD the just-written key — so all tasks converge on one {@code
 * kid}. The same lock makes an accidental concurrent rotation safe (rotation should still be an out-of-band
 * single-writer operation). DB is available here: the issuer is published by an {@code ApplicationRunner}
 * after Flyway, and deploy always runs the {@code data} profile alongside {@code wif.enabled}.
 */
@Service
@ConditionalOnProperty(prefix = "wif", name = "enabled", havingValue = "true")
public class IssuerKeyService {

    private static final Logger log = LoggerFactory.getLogger(IssuerKeyService.class);

    /** Constant advisory-lock key for single-writer issuer-key acquisition (distinct from the relay's). */
    static final long ISSUER_KEY_LOCK = 0x5167_0001L;

    private final SecretsManagerClient secrets;
    private final IssuerProperties props;
    private final DataSource dataSource;
    private volatile RSAKey cached;

    public IssuerKeyService(SecretsManagerClient secrets, IssuerProperties props, DataSource dataSource) {
        this.secrets = secrets;
        this.props = props;
        this.dataSource = dataSource;
    }

    /** RSA key including the private part — used to sign workload assertions (Phase 5). */
    public synchronized RSAKey signingKey() {
        if (cached == null) {
            cached = loadOrCreate();
        }
        return cached;
    }

    /** Public-only JWK Set published at /.well-known/jwks.json for Entra to fetch. */
    public JWKSet publicJwkSet() {
        return new JWKSet(signingKey().toPublicJWK());
    }

    /**
     * Acquire the cross-task single-writer lock, then get-or-generate. The lock is held only on the (rare)
     * cache-miss path — first boot, a restart, or rotation — never per assertion.
     */
    private RSAKey loadOrCreate() {
        try (Connection c = dataSource.getConnection()) {
            advisory(c, "pg_advisory_lock");
            try {
                return getOrGenerate();
            } finally {
                advisory(c, "pg_advisory_unlock");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to acquire the issuer-key single-writer lock", e);
        }
    }

    private void advisory(Connection c, String fn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + fn + "(?)")) {
            ps.setLong(1, ISSUER_KEY_LOCK);
            ps.execute();
        }
    }

    private RSAKey getOrGenerate() {
        String name = props.getSigningSecretName();
        String json;
        try {
            json = secrets.getSecretValue(GetSecretValueRequest.builder().secretId(name).build())
                    .secretString();
        } catch (ResourceNotFoundException e) {
            // Secret exists (created by Terraform) but has no version yet → first boot.
            json = null;
        }
        // No version, or a blank/sentinel value written to trigger rotation → generate a fresh key.
        if (json == null || json.isBlank() || !json.contains("\"kty\"")) {
            return generateAndStore(name);
        }
        try {
            RSAKey key = RSAKey.parse(json);
            log.info("Loaded existing issuer signing key kid={}", key.getKeyID());
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse issuer signing key from " + name, e);
        }
    }

    private RSAKey generateAndStore(String name) {
        try {
            RSAKey key = new RSAKeyGenerator(2048)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint(true)
                    .generate();
            secrets.putSecretValue(PutSecretValueRequest.builder()
                    .secretId(name)
                    .secretString(key.toJSONString()) // full (private) JWK JSON, encrypted by the CMK
                    .build());
            log.info("Generated new issuer signing key kid={} and stored it in {}", key.getKeyID(), name);
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate/store issuer signing key", e);
        }
    }
}
