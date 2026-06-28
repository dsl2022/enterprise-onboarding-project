package com.eop.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The RBAC spine: union resolution, most-permissive scope, ABAC ownership, separation of duties, the
 * impersonation laundering guard, and the display-role precedence — all from the frozen matrix + CR-1056.
 */
class AuthorizationServiceTest {

    private final AuthorizationService authz = new AuthorizationService();

    private CurrentPrincipal principal(Set<PortalRole> real, PortalRole impersonated) {
        return new CurrentPrincipal("real-user", "Real User", "real@eop", real, impersonated);
    }

    private record Resource(String ownerId, java.util.Collection<String> teamMemberIds) implements Ownable {
    }

    private record Req(String requesterId, String submittedById) implements SodSubject {
    }

    // ---- union + most-permissive scope ----

    @Test
    void single_role_scope_is_the_role_scope() {
        var owner = principal(Set.of(PortalRole.APPLICATION_OWNER), null);
        assertThat(authz.scopeFor(owner.effectiveRoles(), Permission.APP_READ)).isEqualTo(Scope.OWN);
    }

    @Test
    void union_takes_the_most_permissive_scope_and_keeps_disjoint_grants() {
        // APPLICATION_OWNER (app.read OWN, app.create OWN) + AUDITOR (app.read ALL) →
        // app.read becomes ALL (auditor wins), app.create stays from the owner role.
        var multi = principal(Set.of(PortalRole.APPLICATION_OWNER, PortalRole.AUDITOR), null);
        assertThat(authz.scopeFor(multi.effectiveRoles(), Permission.APP_READ)).isEqualTo(Scope.ALL);
        assertThat(authz.has(multi, Permission.APP_CREATE)).isTrue();
    }

    @Test
    void permission_not_granted_by_any_role_is_denied() {
        var auditor = principal(Set.of(PortalRole.AUDITOR), null);
        assertThat(authz.has(auditor, Permission.APP_DECIDE)).isFalse();
        assertThatThrownBy(() -> authz.require(auditor, Permission.APP_DECIDE))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).reason())
                .isEqualTo(ForbiddenException.Reason.PERMISSION);
    }

    // ---- ABAC ownership ----

    @Test
    void own_scope_requires_ownership_by_the_real_principal() {
        var owner = principal(Set.of(PortalRole.APPLICATION_OWNER), null);
        var mine = new Resource("real-user", List.of());
        var theirs = new Resource("someone-else", List.of());

        assertThatCode(() -> authz.require(owner, Permission.APP_UPDATE, mine)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authz.require(owner, Permission.APP_UPDATE, theirs))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).reason())
                .isEqualTo(ForbiddenException.Reason.OWNERSHIP);
    }

    @Test
    void all_scope_ignores_ownership() {
        var admin = principal(Set.of(PortalRole.ADMIN), null);
        var theirs = new Resource("someone-else", List.of());
        assertThatCode(() -> authz.require(admin, Permission.APP_UPDATE, theirs)).doesNotThrowAnyException();
    }

    // ---- separation of duties ----

    @Test
    void decision_blocked_when_real_principal_is_the_requester_or_submitter() {
        var ops = principal(Set.of(PortalRole.SSO_OPERATIONS), null);
        assertThatThrownBy(() -> authz.requireDecision(ops, Permission.APP_DECIDE, new Req("real-user", "x")))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).reason())
                .isEqualTo(ForbiddenException.Reason.SEPARATION_OF_DUTIES);
        assertThatCode(() -> authz.requireDecision(ops, Permission.APP_DECIDE, new Req("other", "other")))
                .doesNotThrowAnyException();
    }

    // ---- impersonation: permissions reduce, identity (SoD) does not ----

    @Test
    void impersonation_reduces_permissions_to_the_effective_role() {
        // Real Super Admin impersonating READ_ONLY cannot decide — effective role lacks the permission.
        var superImpersonatingReadOnly = principal(Set.of(PortalRole.SUPER_ADMIN), PortalRole.READ_ONLY);
        assertThat(authz.has(superImpersonatingReadOnly, Permission.APP_DECIDE)).isFalse();
    }

    @Test
    void impersonation_cannot_launder_self_approval() {
        // Submit as self, impersonate SSO_OPERATIONS (which CAN decide), approve own request → SoD blocks
        // it because identity stays the real Super Admin on both sides.
        var laundering = principal(Set.of(PortalRole.SUPER_ADMIN), PortalRole.SSO_OPERATIONS);
        assertThat(authz.has(laundering, Permission.APP_DECIDE)).isTrue(); // effective role can decide …
        assertThatThrownBy(() -> authz.requireDecision(laundering, Permission.APP_DECIDE,
                new Req("real-user", "real-user"))) // … but SoD sees the real id
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).reason())
                .isEqualTo(ForbiddenException.Reason.SEPARATION_OF_DUTIES);
    }

    // ---- display role (precedence only; never an auth input) ----

    @Test
    void display_role_is_the_most_privileged_held_role() {
        assertThat(authz.displayRole(Set.of(PortalRole.APPLICATION_OWNER, PortalRole.AUDITOR)))
                .isEqualTo(PortalRole.AUDITOR);
        assertThat(authz.displayRole(Set.of(PortalRole.ADMIN, PortalRole.READ_ONLY)))
                .isEqualTo(PortalRole.ADMIN);
    }

    @Test
    void display_role_floors_to_read_only_when_no_roles() {
        assertThat(authz.displayRole(Set.of())).isEqualTo(PortalRole.READ_ONLY);
    }
}
