package com.eop.auth;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import com.eop.platform.PrincipalFactory;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Identity endpoint (frozen contract {@code GET /api/v1/me}): the current principal, the display role,
 * the authorizing role set, and impersonation state — resolved from the OIDC session plus the
 * session-stored impersonation overlay.
 */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final PrincipalFactory principalFactory;
    private final ImpersonationService impersonation;
    private final AuthorizationService authz;

    public MeController(PrincipalFactory principalFactory, ImpersonationService impersonation,
            AuthorizationService authz) {
        this.principalFactory = principalFactory;
        this.impersonation = impersonation;
        this.authz = authz;
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal OidcUser oidcUser, HttpSession session) {
        if (oidcUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        PortalRole impersonated = impersonation.current(session).orElse(null);
        CurrentPrincipal principal = principalFactory.from(oidcUser, impersonated);
        return MeResponse.from(principal, authz.displayRole(principal.effectiveRoles()));
    }
}
