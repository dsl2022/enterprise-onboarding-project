package com.eop.authz;

/**
 * Thrown when an authorization check fails — insufficient permission, failed ABAC ownership, or a
 * separation-of-duties violation. The {@code platform} RFC-7807 handler maps this to a 403 problem+json.
 * The {@link #reason} is a stable, non-sensitive code (not the message) for the response/audit.
 */
public class ForbiddenException extends RuntimeException {

    /** Why the check failed — distinguishes permission vs ownership vs SoD without leaking detail. */
    public enum Reason {
        PERMISSION,
        OWNERSHIP,
        SEPARATION_OF_DUTIES
    }

    private final Reason reason;

    public ForbiddenException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
