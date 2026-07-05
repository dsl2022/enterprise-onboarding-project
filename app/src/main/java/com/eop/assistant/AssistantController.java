package com.eop.assistant;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.platform.NotImplementedException;
import com.eop.platform.PrincipalFactory;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The assistant surface (frozen contract). {@code /assistant/chat} is <b>Rung 1 — advisory form-fill</b> when
 * {@link AssistantChatService} is present ({@code eop.assistant.enabled=true} + a model backend); otherwise it
 * stays a 501 stub. {@code /assistant/actions/{id}/approve} remains 501 (Rung-3 write-actions are deferred).
 *
 * <p>The gate runs <b>before</b> anything else: a caller must be authenticated (401) and hold {@code assistant.use}
 * (403) — so an un-permissioned role (AUDITOR/READ_ONLY) never learns whether the feature is live. Only once the
 * gate passes does the chat endpoint delegate to the service (or 501 if disabled); the service enforces body
 * validation (422). Approve short-circuits to 501 before any validation, as before.
 */
@RestController
@RequestMapping("/api/v1")
public class AssistantController {

    private static final String STUB_MESSAGE =
            "The assistant is not implemented in v1 (the wizard/RAG/tools track is deferred).";

    private final AuthorizationService authz;
    private final PrincipalFactory principalFactory;
    private final ObjectProvider<AssistantChatService> chatService;

    public AssistantController(AuthorizationService authz, PrincipalFactory principalFactory,
            ObjectProvider<AssistantChatService> chatService) {
        this.authz = authz;
        this.principalFactory = principalFactory;
        this.chatService = chatService;
    }

    @PostMapping("/assistant/chat")
    public AssistantChatResponse chat(@RequestBody(required = false) AssistantChatRequest body,
            @AuthenticationPrincipal OidcUser oidc, HttpSession session) {
        gate(oidc, session);
        AssistantChatService service = chatService.getIfAvailable();
        if (service == null) {
            throw new NotImplementedException(STUB_MESSAGE); // Rung 1 not enabled — stays a stub
        }
        return service.chat(body);
    }

    @PostMapping("/assistant/actions/{id}/approve")
    public void approve(@AuthenticationPrincipal OidcUser oidc, HttpSession session, @PathVariable String id) {
        gate(oidc, session);
        throw new NotImplementedException(STUB_MESSAGE);
    }

    /** Authenticate (401) + require {@code assistant.use} (403), before the endpoint reveals it's a stub. */
    private void gate(OidcUser oidc, HttpSession session) {
        if (oidc == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        CurrentPrincipal principal = principalFactory.from(oidc, session);
        authz.require(principal, Permission.ASSISTANT_USE);
    }
}
