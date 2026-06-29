package com.eop.platform;

import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import jakarta.servlet.http.HttpSession;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds the {@link CurrentPrincipal} from the OIDC session principal plus the (session-stored)
 * impersonation overlay. Portal RBAC comes from the Entra app-roles (`roles`) claim — NOT group
 * membership. The user id is the Entra object id (`oid`), the stable identifier also used for app-role
 * assignments and (Phase 6) audit attribution; it falls back to `sub` if `oid` is absent.
 */
@Component
public class PrincipalFactory {

    /** Session attribute holding the active impersonation overlay (a {@link PortalRole}), or absent. */
    public static final String IMPERSONATION_SESSION_KEY = "eop.impersonatedRole";

    /** Build the principal, reading any impersonation overlay straight from the session. */
    public CurrentPrincipal from(OidcUser oidcUser, HttpSession session) {
        Object value = session == null ? null : session.getAttribute(IMPERSONATION_SESSION_KEY);
        return from(oidcUser, value instanceof PortalRole role ? role : null);
    }

    public CurrentPrincipal from(OidcUser oidcUser, PortalRole impersonatedRole) {
        String userId = StringUtils.hasText(oidcUser.getClaimAsString("oid"))
                ? oidcUser.getClaimAsString("oid")
                : oidcUser.getSubject();

        String name = StringUtils.hasText(oidcUser.getFullName())
                ? oidcUser.getFullName()
                : oidcUser.getClaimAsString("name");

        String email = StringUtils.hasText(oidcUser.getEmail())
                ? oidcUser.getEmail()
                : oidcUser.getPreferredUsername();

        return new CurrentPrincipal(userId, name, email, rolesFromClaim(oidcUser), impersonatedRole);
    }

    private Set<PortalRole> rolesFromClaim(OidcUser oidcUser) {
        List<String> claim = oidcUser.getClaimAsStringList("roles");
        Set<PortalRole> roles = EnumSet.noneOf(PortalRole.class);
        if (claim != null) {
            for (String value : claim) {
                PortalRole.fromClaim(value).ifPresent(roles::add);
            }
        }
        return roles;
    }
}
