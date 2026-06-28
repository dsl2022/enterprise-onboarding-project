package com.eop.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow 1 (user SSO) — BFF confidential OIDC client against Microsoft Entra.
 *
 * <p>Phase 1 ships honest stubs so the contract and routes exist; the real Auth Code + PKCE flow,
 * ID-token validation, and server-side session land in Phase 4. Tokens will never reach the browser.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final ResponseEntity<Map<String, Object>> NOT_YET = ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .body(Map.of("error", "not_implemented", "flow", "Flow 1 (SSO) — implemented in Phase 4"));

    /** Redirects to Entra /authorize (Auth Code + PKCE, state). */
    @GetMapping("/login")
    public ResponseEntity<Map<String, Object>> login() {
        return NOT_YET;
    }

    /** Entra redirect target: validate state, exchange code (+PKCE), validate ID token, start session. */
    @GetMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback() {
        return NOT_YET;
    }

    /** Clears the server-side session and the cookie. */
    @GetMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        return NOT_YET;
    }

    /** Returns the signed-in user's basic claims — the Flow 1 proof. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        return NOT_YET;
    }
}
