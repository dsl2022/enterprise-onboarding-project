package com.eop.teams;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.request.RequestEntity;
import com.eop.request.RequestStatus;
import com.eop.request.RequestType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pin A regression guard: onboarding {@code Application} co-ownership via teams is DORMANT in v1.
 * {@code RequestEntity} must keep the empty {@code teamMemberIds()} default so being added to a team does
 * NOT silently confer co-ownership of apps (that's the deferred {@code TeamMembershipResolver} port). If
 * someone wires team resolution onto the request aggregate, this fails and forces the decision to be
 * deliberate. Plain unit test — no Spring.
 */
class TeamCoOwnershipInvariantTest {

    @Test
    void request_entity_confers_no_team_co_ownership_in_v1() {
        RequestEntity req = new RequestEntity(UUID.randomUUID(), RequestType.ONBOARDING,
                RequestStatus.DRAFT, "owner-1", "owner-1", "{\"name\":\"x\",\"team\":[\"team-a\"]}");
        assertThat(req.teamMemberIds()).isEmpty(); // app.team[] does NOT resolve to co-owners in v1
    }
}
