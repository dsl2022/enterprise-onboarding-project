package com.eop.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.TestcontainersConfig;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import com.eop.onboarding.ProvisioningService;
import com.eop.request.Decision;
import com.eop.request.RequestService;
import com.eop.request.RequestStatus;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The access reaper recovers a stuck PROVISIONING access request, and the workers are TYPE-SCOPED — the
 * onboarding worker must not touch an access row (it would try to create an app registration for it).
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class AccessReaperTest {

    @Autowired
    AccessService access;

    @Autowired
    AccessProvisioningService accessWorker;

    @Autowired
    ProvisioningService onboardingWorker;

    @Autowired
    RequestService engine;

    private CurrentPrincipal principal(String id, PortalRole role) {
        return new CurrentPrincipal(id, "U " + id, id + "@eop", Set.of(role), null);
    }

    private UUID stuckApprovedAccessRequest(String suffix) {
        var owner = principal("owner-" + suffix, PortalRole.APPLICATION_OWNER);
        var ops = principal("ops-" + suffix, PortalRole.SSO_OPERATIONS);
        var created = access.create(owner, new AccessRequestCreate("aws-dev", "need it", null));
        UUID id = UUID.fromString(created.request().id());
        access.decide(ops, id, new DecisionRequest(Decision.APPROVE, "ok"), null);
        // Claim it but never complete — arm the lease in the PAST so the reaper sees it as due.
        engine.markProvisioning(id, Instant.now().minusSeconds(1));
        return id;
    }

    @Test
    void access_reaper_recovers_a_stuck_request() {
        UUID id = stuckApprovedAccessRequest("reap");
        assertThat(engine.get(id).getStatus()).isEqualTo(RequestStatus.PROVISIONING);

        accessWorker.runOnce(); // pass-2 reaper picks up the due stuck row

        assertThat(engine.get(id).getStatus()).isEqualTo(RequestStatus.GRANTED);
    }

    @Test
    void onboarding_worker_ignores_access_rows() {
        UUID id = stuckApprovedAccessRequest("scope");

        onboardingWorker.runOnce(); // type-scoped to ONBOARDING — must NOT touch this access row

        assertThat(engine.get(id).getStatus()).isEqualTo(RequestStatus.PROVISIONING);

        accessWorker.runOnce(); // the access worker does
        assertThat(engine.get(id).getStatus()).isEqualTo(RequestStatus.GRANTED);
    }
}
