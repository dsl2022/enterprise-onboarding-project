package com.eop.auth;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.platform.PrincipalFactory;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Impersonation endpoints (frozen contract {@code POST}/{@code DELETE /api/v1/impersonation}). Only a
 * <b>real</b> Super Admin may impersonate — the {@code impersonate} permission is checked against the
 * real roles, not the effective overlay, so an active impersonation can't be used to escalate. While
 * impersonating, permissions reduce to the target role but identity/SoD/audit stay the real Super Admin.
 */
@RestController
@RequestMapping("/api/v1")
public class ImpersonationController {

    private final PrincipalFactory principalFactory;
    private final ImpersonationService impersonation;
    private final AuthorizationService authz;

    public ImpersonationController(PrincipalFactory principalFactory, ImpersonationService impersonation,
            AuthorizationService authz) {
        this.principalFactory = principalFactory;
        this.impersonation = impersonation;
        this.authz = authz;
    }

    @PostMapping("/impersonation")
    public MeResponse start(@AuthenticationPrincipal OidcUser oidcUser,
            @RequestBody ImpersonationRequestDto body, HttpSession session) {
        if (oidcUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (body == null || body.role() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "role is required");
        }
        // Capability of the REAL principal (impersonatedRole=null), never the current effective overlay.
        requireImpersonatePermission(oidcUser);
        impersonation.start(session, body.role());
        CurrentPrincipal now = principalFactory.from(oidcUser, body.role());
        return MeResponse.from(now, authz.displayRole(now.effectiveRoles()));
    }

    @DeleteMapping("/impersonation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@AuthenticationPrincipal OidcUser oidcUser, HttpSession session) {
        if (oidcUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        requireImpersonatePermission(oidcUser);
        impersonation.stop(session);
    }

    private void requireImpersonatePermission(OidcUser oidcUser) {
        CurrentPrincipal real = principalFactory.from(oidcUser, null);
        authz.require(real, Permission.IMPERSONATE);
    }
}
