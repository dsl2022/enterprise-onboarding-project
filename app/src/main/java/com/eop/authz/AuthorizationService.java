package com.eop.authz;

import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The single service-layer authorization entry point. Callers ask for a <b>permission</b> (never a
 * role); the answer is the union of the principal's effective roles, at the most-permissive scope, plus
 * ABAC ownership and separation-of-duties where applicable.
 *
 * <p>Resolution rules (frozen RBAC matrix + CR-1056):
 * <ul>
 *   <li><b>Union:</b> a permission is granted if any effective role grants it.</li>
 *   <li><b>Most-permissive scope:</b> {@code ALL} from any role overrides {@code OWN}; {@code OWN}
 *       applies only when every granting role is {@code OWN}.</li>
 *   <li><b>ABAC:</b> {@code OWN} permissions require the real principal to own the resource.</li>
 *   <li><b>SoD:</b> a decision is rejected if the real principal requested or submitted the request.</li>
 * </ul>
 */
@Service
public class AuthorizationService {

    /** Effective scope for a permission across a role set (most permissive; {@link Scope#NONE} if none). */
    public Scope scopeFor(Set<PortalRole> roles, Permission permission) {
        Scope effective = Scope.NONE;
        for (PortalRole role : roles) {
            effective = effective.mostPermissive(RolePermissions.scope(role, permission));
            if (effective == Scope.ALL) {
                break;
            }
        }
        return effective;
    }

    /** Does the principal hold the permission at any scope (against effective roles)? No side effects. */
    public boolean has(CurrentPrincipal principal, Permission permission) {
        return scopeFor(principal.effectiveRoles(), permission) != Scope.NONE;
    }

    /**
     * Display-only role: the most-privileged of the given roles, or {@code READ_ONLY} as the floor for an
     * empty set. Never an authorization input (the server checks permissions as the union).
     */
    public PortalRole displayRole(Set<PortalRole> roles) {
        return roles.stream().max((a, b) -> Integer.compare(a.rank(), b.rank())).orElse(PortalRole.READ_ONLY);
    }

    /** Require an unscoped permission (e.g. {@code impersonate}); 403 if no effective role grants it. */
    public void require(CurrentPrincipal principal, Permission permission) {
        if (scopeFor(principal.effectiveRoles(), permission) == Scope.NONE) {
            throw new ForbiddenException(ForbiddenException.Reason.PERMISSION,
                    "missing permission " + permission.code());
        }
    }

    /**
     * Require a permission against a specific resource. {@code OWN} scope enforces ABAC ownership against
     * the <b>real</b> principal (impersonation does not grant ownership of someone else's resource).
     */
    public void require(CurrentPrincipal principal, Permission permission, Ownable resource) {
        Scope scope = scopeFor(principal.effectiveRoles(), permission);
        if (scope == Scope.NONE) {
            throw new ForbiddenException(ForbiddenException.Reason.PERMISSION,
                    "missing permission " + permission.code());
        }
        if (scope == Scope.OWN && !owns(principal, resource)) {
            throw new ForbiddenException(ForbiddenException.Reason.OWNERSHIP,
                    "not owner of resource for " + permission.code());
        }
    }

    /**
     * Require a decision permission AND separation of duties: the real principal must not be the
     * request's requester or submitter, regardless of role or impersonation.
     */
    public void requireDecision(CurrentPrincipal principal, Permission permission, SodSubject request) {
        require(principal, permission);
        String real = principal.realUserId();
        if (real != null && (real.equals(request.requesterId()) || real.equals(request.submittedById()))) {
            throw new ForbiddenException(ForbiddenException.Reason.SEPARATION_OF_DUTIES,
                    "requester cannot be the approver");
        }
    }

    private boolean owns(CurrentPrincipal principal, Ownable resource) {
        String real = principal.realUserId();
        if (real == null) {
            return false;
        }
        return real.equals(resource.ownerId()) || resource.teamMemberIds().contains(real);
    }
}
