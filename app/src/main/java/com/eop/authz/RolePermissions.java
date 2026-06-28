package com.eop.authz;

import java.util.EnumMap;
import java.util.Map;

/**
 * The frozen RBAC matrix (docs/api/rbac-matrix.md) transcribed as data. Each role maps a permission to
 * the {@link Scope} it grants; absent entries are {@link Scope#NONE}. This is the single source the
 * {@link AuthorizationService} reads — changing authorization means changing the matrix (and a CR), not
 * scattering role checks through services.
 */
final class RolePermissions {

    private static final Map<PortalRole, Map<Permission, Scope>> MATRIX = build();

    private RolePermissions() {
    }

    /** Scope a single role grants for a permission ({@link Scope#NONE} if not granted). */
    static Scope scope(PortalRole role, Permission permission) {
        return MATRIX.getOrDefault(role, Map.of()).getOrDefault(permission, Scope.NONE);
    }

    private static Map<PortalRole, Map<Permission, Scope>> build() {
        Map<PortalRole, Map<Permission, Scope>> m = new EnumMap<>(PortalRole.class);
        for (PortalRole r : PortalRole.values()) {
            m.put(r, new EnumMap<>(Permission.class));
        }

        // SUPER_ADMIN — everything, plus impersonation.
        for (Permission p : Permission.values()) {
            m.get(PortalRole.SUPER_ADMIN).put(p, Scope.ALL);
        }

        // ADMIN — full access across all resources, but NOT impersonation.
        for (Permission p : Permission.values()) {
            if (p != Permission.IMPERSONATE) {
                m.get(PortalRole.ADMIN).put(p, Scope.ALL);
            }
        }

        // APPLICATION_OWNER — own apps + own team; submit requests; request/hold access.
        Map<Permission, Scope> owner = m.get(PortalRole.APPLICATION_OWNER);
        owner.put(Permission.APP_READ, Scope.OWN);
        owner.put(Permission.APP_CREATE, Scope.OWN);
        owner.put(Permission.APP_UPDATE, Scope.OWN);
        owner.put(Permission.APP_SUBMIT, Scope.OWN);
        owner.put(Permission.CATALOG_READ, Scope.ALL);
        owner.put(Permission.ACCESS_REQUEST, Scope.ALL);
        owner.put(Permission.ACCESS_READ, Scope.OWN);
        owner.put(Permission.MYACCESS_READ, Scope.ALL);
        owner.put(Permission.MYACCESS_REMOVAL_REQUEST, Scope.ALL);
        owner.put(Permission.TEAM_READ, Scope.OWN);
        owner.put(Permission.TEAM_MANAGE, Scope.OWN);
        owner.put(Permission.NOTIFICATIONS_READ, Scope.ALL);
        owner.put(Permission.ASSISTANT_USE, Scope.ALL);

        // SSO_OPERATIONS — review/approve/provision; rotate secrets.
        Map<Permission, Scope> ops = m.get(PortalRole.SSO_OPERATIONS);
        ops.put(Permission.APP_READ, Scope.ALL);
        ops.put(Permission.APP_DECIDE, Scope.ALL);
        ops.put(Permission.APP_PROVISION, Scope.ALL);
        ops.put(Permission.CATALOG_READ, Scope.ALL);
        ops.put(Permission.ACCESS_REQUEST, Scope.ALL);
        ops.put(Permission.ACCESS_READ, Scope.ALL);
        ops.put(Permission.ACCESS_DECIDE, Scope.ALL);
        ops.put(Permission.MYACCESS_READ, Scope.ALL);
        ops.put(Permission.MYACCESS_REMOVAL_REQUEST, Scope.ALL);
        ops.put(Permission.REVIEW_READ, Scope.ALL);
        ops.put(Permission.TEAM_READ, Scope.ALL);
        ops.put(Permission.SECRET_ROTATE, Scope.ALL);
        ops.put(Permission.AUDIT_READ, Scope.ALL);
        ops.put(Permission.NOTIFICATIONS_READ, Scope.ALL);
        ops.put(Permission.ASSISTANT_USE, Scope.ALL);

        // AUDITOR — read-only, audit-focused.
        Map<Permission, Scope> auditor = m.get(PortalRole.AUDITOR);
        auditor.put(Permission.APP_READ, Scope.ALL);
        auditor.put(Permission.CATALOG_READ, Scope.ALL);
        auditor.put(Permission.ACCESS_READ, Scope.ALL);
        auditor.put(Permission.REVIEW_READ, Scope.ALL);
        auditor.put(Permission.TEAM_READ, Scope.ALL);
        auditor.put(Permission.AUDIT_READ, Scope.ALL);
        auditor.put(Permission.NOTIFICATIONS_READ, Scope.ALL);

        // READ_ONLY — read-only views, no actions.
        Map<Permission, Scope> readOnly = m.get(PortalRole.READ_ONLY);
        readOnly.put(Permission.APP_READ, Scope.ALL);
        readOnly.put(Permission.CATALOG_READ, Scope.ALL);
        readOnly.put(Permission.ACCESS_READ, Scope.OWN);
        readOnly.put(Permission.MYACCESS_READ, Scope.ALL);
        readOnly.put(Permission.TEAM_READ, Scope.ALL);
        readOnly.put(Permission.NOTIFICATIONS_READ, Scope.ALL);

        return m;
    }
}
