package com.eop.auth;

import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import java.util.List;

/**
 * The frozen {@code Me} schema. {@code role} is display-only (most-privileged of the held roles);
 * {@code roles} is the authorizing set (the union). While impersonating, both reflect the
 * <b>effective</b> (impersonated) role so the UI gates to the reduced view — the real identity is still
 * conveyed by {@code isSuperAdmin=true} and {@code impersonating.role}. {@code group} is informational
 * access-governance (not the RBAC source) and is null until the directory module populates it.
 */
public record MeResponse(
        String id,
        String name,
        String email,
        String role,
        List<String> roles,
        String group,
        boolean isSuperAdmin,
        Impersonating impersonating) {

    public record Impersonating(String role) {
    }

    static MeResponse from(CurrentPrincipal principal, PortalRole displayRole) {
        List<String> effectiveRoles = principal.effectiveRoles().stream().map(Enum::name).sorted().toList();
        Impersonating impersonating = principal.isImpersonating()
                ? new Impersonating(principal.impersonatedRole().name())
                : null;
        return new MeResponse(
                principal.realUserId(),
                principal.realName(),
                principal.realEmail(),
                displayRole.name(),
                effectiveRoles,
                null,
                principal.isSuperAdmin(),
                impersonating);
    }
}
