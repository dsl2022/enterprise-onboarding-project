package com.eop.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.eop.TestcontainersConfig;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import com.eop.directory.AppRegistrationProvisioner;
import com.eop.request.Decision;
import com.eop.request.RequestEntity;
import com.eop.request.RequestService;
import com.eop.request.RequestStatus;
import com.eop.request.RequestType;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The stale-PROVISIONING reaper. A request whose claiming task died mid-provision (stuck in PROVISIONING,
 * lease elapsed) is re-claimed and re-provisioned — proving recovery and the backoff that keeps a
 * permanently-failing request from looping hot. The provisioner is mocked so this test asserts the worker
 * (claim/reaper/backoff) logic without a live Graph; the simulated end-to-end path is covered separately.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class ProvisioningReaperTest {

    @Autowired
    RequestService engine;

    @Autowired
    ProvisioningService provisioning;

    @MockBean
    AppRegistrationProvisioner provisioner;

    private CurrentPrincipal principal(String id, PortalRole role) {
        return new CurrentPrincipal(id, "U " + id, id + "@eop", Set.of(role), null);
    }

    /** Drive a request to a stuck PROVISIONING state whose lease is already due. */
    private UUID stuckProvisioningRequest(String suffix) {
        var owner = principal("owner-" + suffix, PortalRole.APPLICATION_OWNER);
        var ops = principal("ops-" + suffix, PortalRole.SSO_OPERATIONS);
        var req = engine.create(RequestType.ONBOARDING, owner.realUserId(), owner.realUserId(),
                "{\"name\":\"reaper-" + suffix + "\"}");
        engine.submit(owner, req.getId(), null);
        engine.decide(ops, req.getId(), Decision.APPROVE, "ok", null);
        // Claim it but never markProvisioned — and arm the lease in the PAST so the reaper sees it as due.
        engine.markProvisioning(req.getId(), Instant.now().minusSeconds(1));
        return req.getId();
    }

    @Test
    void reaper_recovers_a_request_stuck_in_provisioning() {
        when(provisioner.provision(any(UUID.class), anyString())).thenReturn("client-reaped");
        UUID id = stuckProvisioningRequest("ok");

        int provisioned = provisioning.runOnce(); // pass-2 reaper picks up the due stuck row
        assertThat(provisioned).isGreaterThanOrEqualTo(1);

        RequestEntity after = engine.get(id);
        assertThat(after.getStatus()).isEqualTo(RequestStatus.ACTIVE);
        assertThat(after.getExternalRef()).isEqualTo("client-reaped");
    }

    @Test
    void reaper_increments_attempts_and_backs_off_on_repeated_failure() {
        when(provisioner.provision(any(UUID.class), anyString()))
                .thenThrow(new RuntimeException("Graph unavailable"));
        UUID id = stuckProvisioningRequest("fail");

        provisioning.runOnce(); // reaper re-claims (attempts++ , lease pushed out), then provision throws

        RequestEntity after = engine.get(id);
        assertThat(after.getStatus()).isEqualTo(RequestStatus.PROVISIONING); // no terminal FAILED state yet
        assertThat(after.getProvisionAttempts()).isEqualTo(1);
        assertThat(after.getNextAttemptAt()).isAfter(Instant.now()); // backed off into the future
    }
}
