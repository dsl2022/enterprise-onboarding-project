package com.eop.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.eop.auth.SecurityConfig;
import com.eop.authz.AuthorizationService;
import com.eop.platform.ApiExceptionHandler;
import com.eop.platform.PrincipalFactory;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller wiring for the Rung-1 chat path (service present). The auth gate still runs first (401/403); only an
 * authorized caller reaches the service and gets a 200 with the advisory body. (The disabled → 501 path is
 * covered by {@link AssistantStubTest}, which provides no service bean.)
 */
@WebMvcTest(controllers = AssistantController.class)
@Import({SecurityConfig.class, AuthorizationService.class, PrincipalFactory.class, ApiExceptionHandler.class})
class AssistantChatControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AssistantChatService chatService; // presence flips the endpoint from 501 to live

    private static Consumer<OidcIdToken.Builder> as(String role) {
        return t -> t.subject("u-" + role).claim("oid", "u-" + role).claim("name", "User")
                .claim("email", "u@eop").claim("roles", List.of(role));
    }

    @Test
    void authorized_caller_gets_200_and_the_advisory_body() throws Exception {
        when(chatService.chat(any())).thenReturn(new AssistantChatResponse(
                "Here's a draft.",
                List.of(new ProposedAction("a1", "draftDescription", Map.of("description", "A service."), false))));

        mockMvc.perform(post("/api/v1/assistant/chat").with(oidcLogin().idToken(as("APPLICATION_OWNER")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"help\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Here's a draft."))
                .andExpect(jsonPath("$.proposedActions[0].tool").value("draftDescription"))
                .andExpect(jsonPath("$.proposedActions[0].requiresApproval").value(false));
    }

    @Test
    void unauthenticated_is_401_before_the_service() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void role_without_assistant_use_is_403_before_the_service() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/chat").with(oidcLogin().idToken(as("AUDITOR")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }
}
