package com.eop.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eop.TestcontainersConfig;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.ForbiddenException;
import com.eop.authz.PortalRole;
import com.eop.platform.ConflictException;
import com.eop.platform.NotFoundException;
import com.eop.platform.UnprocessableException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Team CRUD + membership + the ABAC delegation it opens (Phase 5c). Direct CRUD, no engine. Service-level.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class TeamLifecycleTest {

    @Autowired
    TeamService teams;

    private CurrentPrincipal principal(String id, PortalRole role) {
        return new CurrentPrincipal(id, "U " + id, id + "@eop", Set.of(role), null);
    }

    @Test
    void create_list_and_member_count() {
        var owner = principal("owner-t1", PortalRole.APPLICATION_OWNER);
        var team = teams.create(owner, new TeamCreate("Platform " + UUID.randomUUID(), "the platform team"));
        assertThat(team.memberCount()).isZero();

        teams.addMember(owner, UUID.fromString(team.id()), new TeamMemberAdd("member-x"));
        assertThat(teams.list(owner)).anySatisfy(t -> {
            assertThat(t.id()).isEqualTo(team.id());
            assertThat(t.memberCount()).isEqualTo(1);
        });
    }

    @Test
    void duplicate_name_409_and_missing_name_422() {
        var owner = principal("owner-t2", PortalRole.APPLICATION_OWNER);
        String name = "Dup " + UUID.randomUUID();
        teams.create(owner, new TeamCreate(name, null));
        assertThatThrownBy(() -> teams.create(owner, new TeamCreate(name, null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> teams.create(owner, new TeamCreate("  ", null)))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void add_remove_soft_delete_and_reactivate() {
        var owner = principal("owner-t3", PortalRole.APPLICATION_OWNER);
        UUID id = UUID.fromString(teams.create(owner, new TeamCreate("Ops " + UUID.randomUUID(), null)).id());

        teams.addMember(owner, id, new TeamMemberAdd("u-1"));
        teams.addMember(owner, id, new TeamMemberAdd("u-1")); // idempotent — still one active
        assertThat(teams.members(owner, id)).extracting(TeamMember::userId).containsExactly("u-1");

        teams.removeMember(owner, id, "u-1");
        assertThat(teams.members(owner, id)).isEmpty();
        assertThatThrownBy(() -> teams.removeMember(owner, id, "u-1")) // not an active member now
                .isInstanceOf(NotFoundException.class);

        teams.addMember(owner, id, new TeamMemberAdd("u-1")); // re-add reactivates
        assertThat(teams.members(owner, id)).extracting(TeamMember::userId).containsExactly("u-1");
    }

    @Test
    void read_abac_member_can_read_nonmember_403_removal_revokes() {
        var owner = principal("owner-t4", PortalRole.APPLICATION_OWNER);
        var member = principal("member-t4", PortalRole.APPLICATION_OWNER);
        var stranger = principal("stranger-t4", PortalRole.APPLICATION_OWNER);
        UUID id = UUID.fromString(teams.create(owner, new TeamCreate("ABAC " + UUID.randomUUID(), null)).id());
        teams.addMember(owner, id, new TeamMemberAdd("member-t4"));

        assertThat(teams.members(owner, id)).isNotEmpty();                 // creator reads
        assertThat(teams.members(member, id)).isNotEmpty();                // member reads own team (OWN = member)
        assertThatThrownBy(() -> teams.members(stranger, id))              // non-member, OWN scope → 403
                .isInstanceOf(ForbiddenException.class);

        teams.removeMember(owner, id, "member-t4");
        assertThatThrownBy(() -> teams.members(member, id))                // removal revokes the ABAC access
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void manage_admin_sees_all_owner_scoped_stranger_denied() {
        var owner = principal("owner-t5", PortalRole.APPLICATION_OWNER);
        var admin = principal("admin-t5", PortalRole.ADMIN);
        var stranger = principal("stranger-t5", PortalRole.APPLICATION_OWNER);
        UUID id = UUID.fromString(teams.create(owner, new TeamCreate("Manage " + UUID.randomUUID(), null)).id());

        teams.addMember(admin, id, new TeamMemberAdd("via-admin"));        // ADMIN team.manage = ALL
        assertThatThrownBy(() -> teams.addMember(stranger, id, new TeamMemberAdd("nope"))) // not owner/member
                .isInstanceOf(ForbiddenException.class);
        assertThat(teams.list(admin)).isNotEmpty();                        // ADMIN team.read = ALL
    }
}
