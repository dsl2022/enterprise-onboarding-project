package com.eop.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.TestcontainersConfig;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import com.eop.request.Decision;
import com.eop.request.RequestService;
import com.eop.request.RequestStatus;
import com.eop.request.RequestType;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The provisioning worker takes an APPROVED onboarding request to ACTIVE with a (simulated) client id —
 * proving the poll → markProvisioning claim → provision → markProvisioned chain end to end.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class ProvisioningServiceTest {

    @Autowired
    RequestService engine;

    @Autowired
    ProvisioningService provisioning;

    private CurrentPrincipal principal(String id, PortalRole role) {
        return new CurrentPrincipal(id, "U " + id, id + "@eop", Set.of(role), null);
    }

    @Test
    void approved_onboarding_is_provisioned_to_active_with_client_id() {
        var owner = principal("owner-prov", PortalRole.APPLICATION_OWNER);
        var ops = principal("ops-prov", PortalRole.SSO_OPERATIONS);

        var req = engine.create(RequestType.ONBOARDING, "owner-prov", "owner-prov", "{\"name\":\"prov-app\"}");
        engine.submit(owner, req.getId(), null);
        engine.decide(ops, req.getId(), Decision.APPROVE, "ok", null);

        int provisioned = provisioning.runOnce();
        assertThat(provisioned).isGreaterThanOrEqualTo(1);

        var after = engine.get(req.getId());
        assertThat(after.getStatus()).isEqualTo(RequestStatus.ACTIVE);
        assertThat(after.getExternalRef()).isEqualTo("sim-" + req.getId()); // simulate_provisioning client id
    }
}
