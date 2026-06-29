package com.eop.assistant;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.platform.NotImplementedException;
import com.eop.platform.PrincipalFactory;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The assistant surface (frozen contract) — <b>stubbed in v1</b>. Both endpoints return RFC-7807 <b>501</b>;
 * the full wizard (RAG, tools, human-in-the-loop write-actions) is a deferred, separately-reviewed track
 * (see {@code docs/assistant-feature-design-and-guardrails.md}).
 *
 * <p>The gate runs <b>before</b> the 501: a caller must be authenticated (401) and hold {@code assistant.use}
 * (403) — so an un-permissioned role (AUDITOR/READ_ONLY) never even learns the feature is unimplemented, and
 * the authorization is already correct for when the real assistant lands. The stub deliberately does NOT
 * engage validation (422) or the Idempotency-Key store: "not implemented" precedes "is your body valid",
 * and there is nothing to replay.
 */
@RestController
@RequestMapping("/api/v1")
public class AssistantController {

    private static final String STUB_MESSAGE =
            "The assistant is not implemented in v1 (the wizard/RAG/tools track is deferred).";

    private final AuthorizationService authz;
    private final PrincipalFactory principalFactory;

    public AssistantController(AuthorizationService authz, PrincipalFactory principalFactory) {
        this.authz = authz;
        this.principalFactory = principalFactory;
    }

    @PostMapping("/assistant/chat")
    public void chat(@AuthenticationPrincipal OidcUser oidc, HttpSession session) {
        gate(oidc, session);
        throw new NotImplementedException(STUB_MESSAGE);
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
