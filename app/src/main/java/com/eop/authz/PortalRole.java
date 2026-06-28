package com.eop.authz;

import java.util.Optional;

/**
 * The six portal roles, derived from the Entra app-roles (`roles`) claim. The {@link #rank} encodes the
 * display-only precedence from the RBAC matrix (SUPER_ADMIN &gt; ADMIN &gt; SSO_OPERATIONS &gt; AUDITOR
 * &gt; APPLICATION_OWNER &gt; READ_ONLY) used to compute {@code /me.role}. Rank is <b>never</b> an
 * authorization input — authorization is the union of every held role's permissions.
 */
public enum PortalRole {
    READ_ONLY(0),
    APPLICATION_OWNER(1),
    AUDITOR(2),
    SSO_OPERATIONS(3),
    ADMIN(4),
    SUPER_ADMIN(5);

    private final int rank;

    PortalRole(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** Map an Entra app-role claim value to a role; unknown values are ignored (defensive). */
    public static Optional<PortalRole> fromClaim(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (PortalRole r : values()) {
            if (r.name().equals(value)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
