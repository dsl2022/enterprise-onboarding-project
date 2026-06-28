package com.eop.wif;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Flow 2 — Workload Identity Federation. Mints a short-lived JWT signed by our self-hosted issuer's
 * key and exchanges it at Entra for an app-only Microsoft Graph token. No Entra client secret is
 * involved: Entra validates the assertion against the app's federated identity credential by fetching
 * our published JWKS.
 */
@Service
@ConditionalOnProperty(prefix = "wif", name = "enabled", havingValue = "true")
public class WifAssertionService {

    private static final Logger log = LoggerFactory.getLogger(WifAssertionService.class);
    private static final String AUDIENCE = "api://AzureADTokenExchange";
    private static final String GRAPH_SCOPE = "https://graph.microsoft.com/.default";

    private final IssuerKeyService keys;
    private final IssuerProperties props;
    private final RestClient http = RestClient.create();

    private volatile String cachedToken;
    private volatile Instant cachedExpiry = Instant.EPOCH;

    public WifAssertionService(IssuerKeyService keys, IssuerProperties props) {
        this.keys = keys;
        this.props = props;
    }

    /** RS256 client assertion: iss/sub/aud must match the Entra federated credential exactly. */
    String mintAssertion() {
        try {
            RSAKey key = keys.signingKey();
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(props.getIssuerHost())
                    .subject(props.getSubject())
                    .audience(AUDIENCE)
                    .issueTime(Date.from(now))
                    .notBeforeTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint workload assertion", e);
        }
    }

    /** App-only Graph access token, cached until ~1 min before expiry. */
    public synchronized String graphToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.getClientId());
        form.add("scope", GRAPH_SCOPE);
        form.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.add("client_assertion", mintAssertion());

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = http.post()
                .uri("https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token", props.getTenantId())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        cachedToken = (String) resp.get("access_token");
        int expiresIn = ((Number) resp.getOrDefault("expires_in", 3600)).intValue();
        cachedExpiry = Instant.now().plusSeconds(expiresIn);
        log.info("WIF exchange OK: obtained Graph app-only token (expires in {}s)", expiresIn);
        return cachedToken;
    }
}
