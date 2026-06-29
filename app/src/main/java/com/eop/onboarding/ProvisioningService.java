package com.eop.onboarding;

import com.eop.directory.AppRegistrationProvisioner;
import com.eop.platform.ConflictException;
import com.eop.request.RequestEntity;
import com.eop.request.RequestService;
import com.eop.request.RequestStatus;
import com.eop.request.RequestType;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The async provisioning worker. {@link #runOnce()} polls APPROVED onboarding requests and provisions
 * each; the {@link ProvisioningScheduler} calls it on a fixed delay in deployment, and tests call it
 * directly (deterministic). Under ≥2 tasks the guarded {@code markProvisioning} claim ensures exactly one
 * task provisions a given request — the serializer is the work-lock.
 */
@Service
public class ProvisioningService {

    private static final int BATCH = 50;

    private final RequestService engine;
    private final AppRegistrationProvisioner provisioner;

    public ProvisioningService(RequestService engine, AppRegistrationProvisioner provisioner) {
        this.engine = engine;
        this.provisioner = provisioner;
    }

    /** Provision all currently-APPROVED onboarding requests; returns how many reached ACTIVE. */
    public int runOnce() {
        int provisioned = 0;
        for (RequestEntity r : engine.list(RequestType.ONBOARDING, null, RequestStatus.APPROVED,
                PageRequest.of(0, BATCH))) {
            if (provisionOne(r.getId())) {
                provisioned++;
            }
        }
        return provisioned;
    }

    private boolean provisionOne(UUID id) {
        RequestEntity claimed;
        try {
            claimed = engine.markProvisioning(id); // APPROVED → PROVISIONING; one task wins
        } catch (ConflictException e) {
            return false; // another task claimed it, or it already moved
        }
        try {
            // DB-first idempotency: if a prior attempt created the app but lost the markProvisioned write,
            // external_ref is already set — reuse it instead of creating again.
            String clientId = claimed.getExternalRef() != null
                    ? claimed.getExternalRef()
                    : provisioner.provision(id, "eop-onboarded-" + id);
            engine.markProvisioned(id, clientId);
            return true;
        } catch (RuntimeException ex) {
            // Bounded-retry exhaustion surfaces as a non-transition event; the request stays PROVISIONING.
            engine.provisioningFailed(id, ex.getMessage());
            return false;
        }
    }
}
