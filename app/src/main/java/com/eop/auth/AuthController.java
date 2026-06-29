package com.eop.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Flow 1 (user SSO) endpoints. The Auth Code + PKCE exchange, ID-token validation, and session are
 * handled by Spring Security's OAuth2 client (see {@link SecurityConfig}); these endpoints are the
 * thin BFF surface the UI talks to. Tokens never leave the server.
 *
 * <p>{@code /auth/logout} is handled by the security filter chain (GET → clear session → redirect).
 */
@RestController
public class AuthController {

    /**
     * Kicks off login by handing to Spring's authorization-request endpoint for the `entra` client.
     * Optionally remembers a same-origin {@code returnTo} so the SPA (served at {@code /app}) lands back
     * where it started after the OAuth round-trip, rather than the default {@code /}. The value survives
     * the round-trip in the session (migrated across Spring's session-fixation change) and is consumed by
     * the success handler in {@link SecurityConfig}. Validated to a relative path → no open redirect.
     */
    @GetMapping("/auth/login")
    public RedirectView login(
            @RequestParam(name = "returnTo", required = false) String returnTo,
            HttpServletRequest request) {
        if (SafeRelativePath.isValid(returnTo)) {
            request.getSession(true).setAttribute(SafeRelativePath.RETURN_TO_SESSION_ATTR, returnTo);
        }
        return new RedirectView("/oauth2/authorization/entra");
    }

    /** Flow 1 proof: the signed-in user's basic claims (or 401 if no session). */
    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal OidcUser user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("authenticated", false));
        }
        var claims = new LinkedHashMap<String, Object>();
        claims.put("authenticated", true);
        claims.put("name", user.getFullName());
        claims.put("email", user.getEmail() != null ? user.getEmail() : user.getPreferredUsername());
        claims.put("sub", user.getSubject());
        claims.put("tenant", user.getClaimAsString("tid"));
        return ResponseEntity.ok(claims);
    }
}
