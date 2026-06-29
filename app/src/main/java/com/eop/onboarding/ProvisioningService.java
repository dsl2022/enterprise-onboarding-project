package com.eop.onboarding;

import com.eop.directory.AppRegistrationProvisioner;
import com.eop.platform.ConflictException;
import com.eop.request.RequestEntity;
import com.eop.request.RequestService;
import com.eop.request.RequestStatus;
import com.eop.request.RequestType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The async provisioning worker. {@link #runOnce()} does two passes: it claims and provisions APPROVED
 * onboarding requests, then reaps rows stuck in PROVISIONING (a task that claimed one then died). The
 * {@link ProvisioningScheduler} calls it on a fixed delay in deployment; tests call it directly.
 *
 * <p>Under ≥2 tasks the guarded {@code markProvisioning} claim (and the guarded reaper re-claim) ensure
 * exactly one task provisions a given request — the serializer is the work-lock.
 *
 * <p><b>Reaper safety:</b> a fresh claim arms a lease ({@code lease-seconds}, sized above worst-case
 * provision duration) so a still-running-but-slow live worker is never double-called. A genuinely-stuck
 * request backs off exponentially per re-attempt (capped) rather than looping hot — there is no terminal
 * FAILED state yet (a future CR), so backoff is what stops a permanent failure from flooding
 * provisioning_failed / notify / audit and hammering Graph.
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);
    private static final int BATCH = 50;

    private final RequestService engine;
    private final AppRegistrationProvisioner provisioner;
    private final ObjectMapper json;

    /** Lease/backoff base — must exceed worst-case provision duration (bounded Graph retries + backoff). */
    @Value("${eop.provisioning.lease-seconds:300}")
    private long leaseSeconds;

    /** Backoff ceiling so a permanently-stuck request retries at most this often. */
    @Value("${eop.provisioning.backoff-cap-seconds:3600}")
    private long backoffCapSeconds;

    public ProvisioningService(RequestService engine, AppRegistrationProvisioner provisioner, ObjectMapper json) {
        this.engine = engine;
        this.provisioner = provisioner;
        this.json = json;
    }

    /** Provision APPROVED onboarding requests, then reap stale PROVISIONING ones; returns how many reached ACTIVE. */
    public int runOnce() {
        int provisioned = 0;
        // Pass 1: fresh claims (APPROVED → PROVISIONING).
        for (RequestEntity r : engine.list(RequestType.ONBOARDING, null, RequestStatus.APPROVED,
                PageRequest.of(0, BATCH))) {
            if (provisionFresh(r.getId())) {
                provisioned++;
            }
        }
        // Pass 2: reap rows stuck in PROVISIONING whose lease/backoff elapsed (onboarding only — type-scoped).
        for (RequestEntity r : engine.findStaleProvisioning(RequestType.ONBOARDING, Instant.now(),
                PageRequest.of(0, BATCH))) {
            if (reprovisionStale(r)) {
                provisioned++;
            }
        }
        return provisioned;
    }

    private boolean provisionFresh(UUID id) {
        RequestEntity claimed;
        try {
            claimed = engine.markProvisioning(id, Instant.now().plusSeconds(leaseSeconds)); // one task wins
        } catch (ConflictException e) {
            return false; // another task claimed it, or it already moved
        }
        return provisionClaimed(claimed);
    }

    private boolean reprovisionStale(RequestEntity stale) {
        long backoff = nextBackoffSeconds(stale.getProvisionAttempts() + 1);
        RequestEntity claimed;
        try {
            claimed = engine.reclaimStaleProvisioning(stale.getId(), Instant.now().plusSeconds(backoff));
        } catch (ConflictException e) {
            return false; // another reaper took it, or it already moved on
        }
        log.warn("Reaping stuck provisioning request {} (attempt {}); next retry in ≤{}s if it fails again",
                claimed.getId(), claimed.getProvisionAttempts(), backoff);
        return provisionClaimed(claimed);
    }

    /** Shared body for both fresh and reaped claims: DB-first idempotency, then provision → markProvisioned. */
    private boolean provisionClaimed(RequestEntity claimed) {
        UUID id = claimed.getId();
        try {
            // DB-first idempotency: if a prior attempt created the app but lost the markProvisioned write,
            // external_ref is already set — reuse it (no Graph call) instead of creating again.
            String clientId = claimed.getExternalRef() != null
                    ? claimed.getExternalRef()
                    : provisioner.provision(id, displayName(claimed));
            engine.markProvisioned(id, clientId);
            return true;
        } catch (RuntimeException ex) {
            // Stays PROVISIONING; the reaper re-attempts after backoff. No terminal FAILED state yet.
            engine.provisioningFailed(id, ex.getMessage());
            return false;
        }
    }

    /** Exponential backoff (base = lease), capped. attempt is 1-based. */
    private long nextBackoffSeconds(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 16); // guard against overflow
        long secs = leaseSeconds << shift;
        return Math.min(secs <= 0 ? backoffCapSeconds : secs, backoffCapSeconds);
    }

    /** Registration display name = the onboarding app's name (human-readable); the requestId tag is the dedup key. */
    private String displayName(RequestEntity claimed) {
        try {
            JsonNode name = json.readTree(claimed.getPayload()).get("name");
            if (name != null && name.isTextual() && !name.asText().isBlank()) {
                return name.asText();
            }
        } catch (Exception e) {
            log.warn("Could not read name from payload of {}; falling back to synthetic display name", claimed.getId());
        }
        return "eop-onboarded-" + claimed.getId();
    }
}
