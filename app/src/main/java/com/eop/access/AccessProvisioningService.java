package com.eop.access;

import com.eop.directory.GroupMembershipProvisioner;
import com.eop.platform.ConflictException;
import com.eop.request.RequestEntity;
import com.eop.request.RequestService;
import com.eop.request.RequestStatus;
import com.eop.request.RequestType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The access provisioning worker. {@link #runOnce()} claims APPROVED access requests and provisions each
 * (grant = add Entra group member, removal = remove), then reaps rows stuck in PROVISIONING. Mirrors the
 * onboarding worker — same guarded claim, lease, and exponential backoff — but type-scoped to ACCESS so
 * the two workers never touch each other's rows. Completion (markProvisioned + the access_grant projection)
 * is atomic via {@link AccessGrantService}; the Graph call stays outside that transaction.
 */
@Service
public class AccessProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(AccessProvisioningService.class);
    private static final int BATCH = 50;
    private static final String KIND_REMOVAL = "removal";

    private final RequestService engine;
    private final GroupMembershipProvisioner provisioner;
    private final AccessGrantService grantService;
    private final ObjectMapper json;

    @Value("${eop.provisioning.lease-seconds:300}")
    private long leaseSeconds;

    @Value("${eop.provisioning.backoff-cap-seconds:3600}")
    private long backoffCapSeconds;

    public AccessProvisioningService(RequestService engine, GroupMembershipProvisioner provisioner,
            AccessGrantService grantService, ObjectMapper json) {
        this.engine = engine;
        this.provisioner = provisioner;
        this.grantService = grantService;
        this.json = json;
    }

    /** Provision APPROVED access requests, then reap stale PROVISIONING ones; returns how many reached GRANTED. */
    public int runOnce() {
        int provisioned = 0;
        for (RequestEntity r : engine.list(RequestType.ACCESS, null, RequestStatus.APPROVED,
                PageRequest.of(0, BATCH))) {
            if (provisionFresh(r.getId())) {
                provisioned++;
            }
        }
        for (RequestEntity r : engine.findStaleProvisioning(RequestType.ACCESS, Instant.now(),
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
            claimed = engine.markProvisioning(id, Instant.now().plusSeconds(leaseSeconds));
        } catch (ConflictException e) {
            return false;
        }
        return provisionClaimed(claimed);
    }

    private boolean reprovisionStale(RequestEntity stale) {
        long backoff = nextBackoffSeconds(stale.getProvisionAttempts() + 1);
        RequestEntity claimed;
        try {
            claimed = engine.reclaimStaleProvisioning(stale.getId(), Instant.now().plusSeconds(backoff));
        } catch (ConflictException e) {
            return false;
        }
        log.warn("Reaping stuck access provisioning {} (attempt {}); next retry in ≤{}s if it fails again",
                claimed.getId(), claimed.getProvisionAttempts(), backoff);
        return provisionClaimed(claimed);
    }

    private boolean provisionClaimed(RequestEntity claimed) {
        UUID id = claimed.getId();
        try {
            JsonNode p = tree(claimed.getPayload());
            String resourceId = text(p, "resourceId");
            String resourceName = text(p, "resourceName");
            String mappedGroup = text(p, "mappedGroup");
            String kind = text(p, "kind");
            String userId = claimed.getRequester();

            if (KIND_REMOVAL.equals(kind)) {
                if (claimed.getExternalRef() == null) {
                    provisioner.removeMember(mappedGroup, userId); // idempotent
                }
                grantService.completeRemoval(id, resourceId, userId, "removed");
            } else {
                // DB-first idempotency: reuse a recorded ref instead of re-adding.
                String ref = claimed.getExternalRef() != null
                        ? claimed.getExternalRef()
                        : provisioner.addMember(mappedGroup, userId); // idempotent
                Instant expiresAt = expiresAt(text(p, "duration"));
                grantService.completeGrant(id, resourceId, resourceName, userId, expiresAt, ref);
            }
            return true;
        } catch (RuntimeException ex) {
            engine.provisioningFailed(id, ex.getMessage());
            return false;
        }
    }

    /** expires_at = now + duration (recorded, NOT enforced in v1). Best-effort ISO-8601; null = permanent. */
    private Instant expiresAt(String duration) {
        if (duration == null || duration.isBlank()) {
            return null;
        }
        Instant now = Instant.now();
        try {
            return now.plus(Duration.parse(duration)); // PT24H, PT720H, ...
        } catch (RuntimeException notDuration) {
            try {
                return now.atZone(ZoneOffset.UTC).plus(Period.parse(duration)).toInstant(); // P30D, P1Y, ...
            } catch (RuntimeException notPeriod) {
                log.warn("Unparseable duration '{}' — recording grant as permanent", duration);
                return null;
            }
        }
    }

    private long nextBackoffSeconds(int attempt) {
        int shift = Math.min(Math.max(attempt - 1, 0), 16);
        long secs = leaseSeconds << shift;
        return Math.min(secs <= 0 ? backoffCapSeconds : secs, backoffCapSeconds);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private JsonNode tree(String payload) {
        try {
            return json.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt access request payload", e);
        }
    }
}
