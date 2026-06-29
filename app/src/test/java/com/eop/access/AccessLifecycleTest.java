package com.eop.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eop.TestcontainersConfig;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.ForbiddenException;
import com.eop.authz.PortalRole;
import com.eop.platform.UnprocessableException;
import com.eop.request.Decision;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The access-governance loop over the engine + simulated group provisioning: catalog read, request →
 * review → decide (SoD on the real principal) → provision to GRANTED, my-access (the projection), removal,
 * and read ABAC. Service-level (no HTTP), simulated provisioner — the real Graph path is 5b.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class AccessLifecycleTest {

    @Autowired
    AccessService access;

    @Autowired
    AccessProvisioningService provisioning;

    private CurrentPrincipal principal(String id, PortalRole role) {
        return new CurrentPrincipal(id, "U " + id, id + "@eop", Set.of(role), null);
    }

    @Test
    void catalog_is_readable_and_filterable() {
        var owner = principal("owner-cat", PortalRole.APPLICATION_OWNER);
        assertThat(access.listCatalog(owner, null, null, null, 20).items()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(access.listCatalog(owner, "AWS", null, null, 20).items())
                .allMatch(r -> r.type().equals("AWS"));
        assertThat(access.listCatalog(owner, null, "HIGH", null, 20).items())
                .allMatch(r -> r.risk().equals("HIGH"));
        assertThat(access.getCatalog(owner, "aws-prod").mappedGroup()).isEqualTo("aws-prod-engineers");
    }

    @Test
    void grant_request_flows_to_granted_and_appears_in_my_access() {
        var owner = principal("owner-grant", PortalRole.APPLICATION_OWNER);
        var ops = principal("ops-grant", PortalRole.SSO_OPERATIONS);

        var created = access.create(owner, new AccessRequestCreate("aws-dev", "need dev access", null));
        assertThat(created.request().status()).isEqualTo("UNDER_REVIEW"); // access auto-advances on create
        assertThat(created.request().kind()).isEqualTo("grant");
        UUID id = UUID.fromString(created.request().id());

        access.decide(ops, id, new DecisionRequest(Decision.APPROVE, "ok"), null);
        provisioning.runOnce();

        assertThat(access.get(ops, id).request().status()).isEqualTo("GRANTED");
        assertThat(access.myAccess(owner)).extracting(MyAccessItem::resourceId).contains("aws-dev");
    }

    @Test
    void unknown_resource_and_missing_justification_are_422() {
        var owner = principal("owner-422", PortalRole.APPLICATION_OWNER);
        assertThatThrownBy(() -> access.create(owner, new AccessRequestCreate("nope", "j", null)))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> access.create(owner, new AccessRequestCreate("aws-dev", " ", null)))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void separation_of_duties_blocks_self_approval() {
        // ops holds both access.request and access.decide — but cannot approve a request they raised.
        var ops = principal("ops-sod", PortalRole.SSO_OPERATIONS);
        var created = access.create(ops, new AccessRequestCreate("aws-dev", "self", null));
        UUID id = UUID.fromString(created.request().id());
        assertThatThrownBy(() -> access.decide(ops, id, new DecisionRequest(Decision.APPROVE, "self"), null))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void read_abac_owner_sees_only_own() {
        var a = principal("owner-a", PortalRole.APPLICATION_OWNER);
        var b = principal("owner-b", PortalRole.APPLICATION_OWNER);
        var created = access.create(a, new AccessRequestCreate("aws-dev", "mine", null));
        UUID id = UUID.fromString(created.request().id());

        assertThat(access.get(a, id).request().requester()).isEqualTo("owner-a"); // owner sees own
        assertThatThrownBy(() -> access.get(b, id)).isInstanceOf(ForbiddenException.class); // not B's
    }

    @Test
    void removal_request_revokes_the_grant_in_my_access() {
        var owner = principal("owner-rem", PortalRole.APPLICATION_OWNER);
        var ops = principal("ops-rem", PortalRole.SSO_OPERATIONS);

        // First hold it.
        var grant = access.create(owner, new AccessRequestCreate("workday-rep", "reporting", "P30D"));
        access.decide(ops, UUID.fromString(grant.request().id()), new DecisionRequest(Decision.APPROVE, "ok"), null);
        provisioning.runOnce();
        assertThat(access.myAccess(owner)).extracting(MyAccessItem::resourceId).contains("workday-rep");

        // Then request removal → review → approve → provision.
        var removal = access.requestRemoval(owner, "workday-rep");
        assertThat(removal.request().kind()).isEqualTo("removal");
        access.decide(ops, UUID.fromString(removal.request().id()), new DecisionRequest(Decision.APPROVE, "ok"), null);
        provisioning.runOnce();

        assertThat(access.myAccess(owner)).extracting(MyAccessItem::resourceId).doesNotContain("workday-rep");
    }

    @Test
    void removal_of_unheld_resource_is_422() {
        var owner = principal("owner-norem", PortalRole.APPLICATION_OWNER);
        assertThatThrownBy(() -> access.requestRemoval(owner, "aws-prod"))
                .isInstanceOf(UnprocessableException.class);
    }
}
