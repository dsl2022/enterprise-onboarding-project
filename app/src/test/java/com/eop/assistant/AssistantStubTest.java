package com.eop.assistant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eop.auth.SecurityConfig;
import com.eop.authz.AuthorizationService;
import com.eop.platform.ApiExceptionHandler;
import com.eop.platform.PrincipalFactory;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The assistant stub (Phase 7): both endpoints return 501 — but only AFTER the auth gate. A caller with no
 * session gets 401; an authenticated role lacking {@code assistant.use} (AUDITOR/READ_ONLY) gets 403 and is
 * never told the feature is unimplemented; an authorized role gets 501. Confirms the gate ordering and that
 * the stub ignores the (documented-but-unused) request body.
 */
@WebMvcTest(controllers = AssistantController.class)
@Import({SecurityConfig.class, AuthorizationService.class, PrincipalFactory.class, ApiExceptionHandler.class})
class AssistantStubTest {

    @Autowired
    MockMvc mockMvc;

    private static Consumer<OidcIdToken.Builder> idToken(String userId, List<String> roles) {
        return t -> t.subject(userId).claim("oid", userId).claim("name", "User " + userId)
                .claim("email", userId + "@eop").claim("roles", roles);
    }

    private Consumer<OidcIdToken.Builder> as(String role) {
        return idToken("u-" + role, List.of(role));
    }

    // ---- /assistant/chat ----

    @Test
    void chat_requires_a_session() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chat_is_forbidden_for_roles_without_assistant_use() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/chat").with(oidcLogin().idToken(as("AUDITOR")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/assistant/chat").with(oidcLogin().idToken(as("READ_ONLY")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void chat_is_501_for_an_authorized_role_ignoring_the_body() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/chat").with(oidcLogin().idToken(as("APPLICATION_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}")) // missing 'message' — stub ignores it
                .andExpect(status().isNotImplemented());
    }

    // ---- /assistant/actions/{id}/approve ----

    @Test
    void approve_requires_a_session() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/actions/abc/approve"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void approve_is_forbidden_without_assistant_use() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/actions/abc/approve").with(oidcLogin().idToken(as("READ_ONLY"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void approve_is_501_for_an_authorized_role() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/actions/abc/approve").with(oidcLogin().idToken(as("ADMIN"))))
                .andExpect(status().isNotImplemented());
    }
}
