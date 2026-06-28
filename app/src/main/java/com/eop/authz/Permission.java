package com.eop.authz;

/**
 * The permission vocabulary — one entry per row of the frozen RBAC matrix (docs/api/rbac-matrix.md).
 * Authorization is always a <b>permission</b> check at the service layer, never a role check. The
 * {@link #code} is the dotted form used in the matrix, problem-details, and (Phase 6) audit records.
 */
public enum Permission {
    APP_READ("app.read"),
    APP_CREATE("app.create"),
    APP_UPDATE("app.update"),
    APP_SUBMIT("app.submit"),
    APP_DECIDE("app.decide"),
    APP_PROVISION("app.provision"),
    CATALOG_READ("catalog.read"),
    ACCESS_REQUEST("access.request"),
    ACCESS_READ("access.read"),
    ACCESS_DECIDE("access.decide"),
    MYACCESS_READ("myaccess.read"),
    MYACCESS_REMOVAL_REQUEST("myaccess.removal.request"),
    REVIEW_READ("review.read"),
    TEAM_READ("team.read"),
    TEAM_MANAGE("team.manage"),
    SECRET_ROTATE("secret.rotate"),
    AUDIT_READ("audit.read"),
    NOTIFICATIONS_READ("notifications.read"),
    IMPERSONATE("impersonate"),
    ASSISTANT_USE("assistant.use");

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
