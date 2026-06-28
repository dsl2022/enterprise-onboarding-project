package com.eop.authz;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * The authenticated subject of every request, carrying the <b>real</b> identity and (when a Super Admin
 * is impersonating) the effective role overlay. The split is the whole point of the impersonation
 * laundering guard:
 * <ul>
 *   <li><b>Permissions</b> evaluate against {@link #effectiveRoles()} — so an impersonated session sees
 *       the reduced role's capabilities.</li>
 *   <li><b>Identity</b> — {@link #realUserId()} for SoD, ABAC ownership, and audit attribution — always
 *       resolves to the real principal, never the impersonated role. Self-approval laundered through
 *       impersonation is therefore blocked (SoD sees the same real id on both sides).</li>
 * </ul>
 */
public record CurrentPrincipal(
        String realUserId,
        String realName,
        String realEmail,
        Set<PortalRole> realRoles,
        PortalRole impersonatedRole) {

    public CurrentPrincipal {
        realRoles = realRoles == null || realRoles.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(realRoles));
    }

    /** Roles authorization is evaluated against: the impersonated role when impersonating, else the real set. */
    public Set<PortalRole> effectiveRoles() {
        return impersonatedRole != null ? Set.of(impersonatedRole) : realRoles;
    }

    public boolean isImpersonating() {
        return impersonatedRole != null;
    }

    /** Identity-level: is the REAL principal a Super Admin (independent of any impersonation overlay)? */
    public boolean isSuperAdmin() {
        return realRoles.contains(PortalRole.SUPER_ADMIN);
    }
}
