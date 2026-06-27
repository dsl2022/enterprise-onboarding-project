package com.eop.wif;

import org.springframework.stereotype.Service;

/**
 * Flow 2a/3b — Workload Identity Federation.
 *
 * <p>At runtime this will: (1) load the RSA signing key from AWS Secrets Manager, (2) mint a
 * short-lived RS256 JWT with {@code iss=https://<issuer-host>}, {@code sub=<workload subject>},
 * {@code aud=api://AzureADTokenExchange}, {@code iat/exp/jti} and the matching {@code kid}, then
 * (3) POST it as a {@code client_assertion} (grant_type=client_credentials,
 * scope=https://graph.microsoft.com/.default) to Entra's token endpoint and cache the returned
 * app-only Graph token until near expiry.
 *
 * <p>The public half of this key is published by the issuer module (S3 + CloudFront) at
 * {@code /.well-known/jwks.json}; Entra fetches it to validate the assertion against the app's
 * federated identity credential (issuer + subject + audience must all match). No Entra client
 * secret is ever involved in this flow.
 *
 * <p>Phase 1 placeholder; minting + exchange implemented in Phase 5. See ADR-0004 (raw REST).
 */
@Service
public class WifAssertionService {
    // TODO(Phase 5): mintWorkloadAssertion() and exchangeForGraphToken().
}
