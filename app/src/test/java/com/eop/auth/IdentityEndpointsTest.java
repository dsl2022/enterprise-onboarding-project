package com.eop.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eop.authz.AuthorizationService;
import com.eop.auth.SecurityConfig;
import com.eop.platform.ApiExceptionHandler;
import com.eop.platform.PrincipalFactory;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract tests for {@code /api/v1/me} and {@code /api/v1/impersonation} across roles. Drives the real
 * controllers + authz + RFC-7807 handler under the (no-auth-profile) open security chain, with the OIDC
 * principal supplied by {@code oidcLogin()} carrying a `roles` claim — exactly how Entra app roles arrive.
 */
@WebMvcTest(controllers = {MeController.class, ImpersonationController.class})
@Import({SecurityConfig.class, AuthorizationService.class, PrincipalFactory.class, ImpersonationService.class,
        ApiExceptionHandler.class})
class IdentityEndpointsTest {

    @Autowired
    MockMvc mockMvc;

    /** An oidcLogin whose id token carries the given app-role claim values. */
    private static Consumer<OidcIdToken.Builder> idToken(String userId, List<String> roles) {
        return t -> t.subject(userId).claim("oid", userId).claim("name", "User " + userId)
                .claim("email", userId + "@eop").claim("roles", roles);
    }

    @Test
    void me_requires_a_session() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void me_returns_union_roles_and_display_role() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                .with(oidcLogin().idToken(idToken("u-multi", List.of("APPLICATION_OWNER", "AUDITOR")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u-multi"))
                .andExpect(jsonPath("$.role").value("AUDITOR"))              // display = most-privileged
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.containsInAnyOrder("APPLICATION_OWNER", "AUDITOR")))
                .andExpect(jsonPath("$.isSuperAdmin").value(false))
                .andExpect(jsonPath("$.group").doesNotExist())               // null → omitted/null
                .andExpect(jsonPath("$.impersonating").doesNotExist());
    }

    @Test
    void non_super_admin_cannot_impersonate() throws Exception {
        mockMvc.perform(post("/api/v1/impersonation")
                .with(oidcLogin().idToken(idToken("u-admin", List.of("ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"READ_ONLY\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("PERMISSION"));
    }

    @Test
    void super_admin_impersonation_round_trip() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var login = oidcLogin().idToken(idToken("u-super", List.of("SUPER_ADMIN")));

        // Begin impersonating READ_ONLY → effective view reduces, identity stays Super Admin.
        mockMvc.perform(post("/api/v1/impersonation").session(session).with(login)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"READ_ONLY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("READ_ONLY"))
                .andExpect(jsonPath("$.roles", org.hamcrest.Matchers.contains("READ_ONLY")))
                .andExpect(jsonPath("$.isSuperAdmin").value(true))
                .andExpect(jsonPath("$.impersonating.role").value("READ_ONLY"));

        // The overlay persists in the (Redis-backed in prod) session.
        mockMvc.perform(get("/api/v1/me").session(session).with(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impersonating.role").value("READ_ONLY"));

        // Stop → back to the real Super Admin view.
        mockMvc.perform(delete("/api/v1/impersonation").session(session).with(login))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me").session(session).with(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.impersonating").doesNotExist());
    }
}
