package com.eop.onboarding;

import com.eop.authz.CurrentPrincipal;
import com.eop.platform.IdempotencyService;
import com.eop.platform.PreconditionFailedException;
import com.eop.platform.PrincipalFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Application onboarding endpoints (frozen contract). Idempotency-Key wraps the creating/transition POSTs
 * (replay → original response); If-Match carries optimistic concurrency to the engine; ETag is emitted on
 * every single-resource response. Read ABAC and the projection live in {@link OnboardingService}.
 */
@RestController
@RequestMapping("/api/v1/applications")
public class ApplicationController {

    private final OnboardingService onboarding;
    private final IdempotencyService idempotency;
    private final PrincipalFactory principalFactory;
    private final ObjectMapper json;

    public ApplicationController(OnboardingService onboarding, IdempotencyService idempotency,
            PrincipalFactory principalFactory, ObjectMapper json) {
        this.onboarding = onboarding;
        this.idempotency = idempotency;
        this.principalFactory = principalFactory;
        this.json = json;
    }

    @PostMapping
    public ResponseEntity<String> create(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestHeader("Idempotency-Key") String key, @RequestBody ApplicationCreate body) {
        CurrentPrincipal principal = principal(oidc, session);
        return idempotency.execute(principal.realUserId(), "POST /applications", key, hash(body), () -> {
            ApplicationView v = onboarding.create(principal, body);
            return new IdempotencyService.Outcome(201, etag(v.version()), v.application());
        });
    }

    @GetMapping
    public ApplicationPage list(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return onboarding.list(principal(oidc, session), status, owner, cursor, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Application> get(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id) {
        ApplicationView v = onboarding.get(principal(oidc, session), id);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(v.version())).body(v.application());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Application> patch(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id, @RequestHeader("If-Match") String ifMatch, @RequestBody ApplicationPatch body) {
        ApplicationView v = onboarding.patch(principal(oidc, session), id, body, parseIfMatch(ifMatch));
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(v.version())).body(v.application());
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<String> submit(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch) {
        CurrentPrincipal principal = principal(oidc, session);
        Integer ifMatchVersion = parseIfMatch(ifMatch);
        return idempotency.execute(principal.realUserId(), "POST /applications/" + id + "/submit", key, "", () -> {
            ApplicationView v = onboarding.submit(principal, id, ifMatchVersion);
            return new IdempotencyService.Outcome(200, etag(v.version()), v.application());
        });
    }

    @PostMapping("/{id}/decision")
    public ResponseEntity<String> decision(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch, @RequestBody DecisionRequest body) {
        CurrentPrincipal principal = principal(oidc, session);
        Integer ifMatchVersion = parseIfMatch(ifMatch);
        return idempotency.execute(principal.realUserId(), "POST /applications/" + id + "/decision", key,
                hash(body), () -> {
                    ApplicationView v = onboarding.decide(principal, id, body, ifMatchVersion);
                    return new IdempotencyService.Outcome(200, etag(v.version()), v.application());
                });
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id) {
        return onboarding.timeline(principal(oidc, session), id);
    }

    // ---- helpers ----

    private CurrentPrincipal principal(OidcUser oidc, HttpSession session) {
        if (oidc == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principalFactory.from(oidc, session);
    }

    private String etag(int version) {
        return "\"" + version + "\"";
    }

    /** Parse If-Match to a version int; {@code *} (any) and absent → null (no version constraint). */
    private Integer parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || ifMatch.equals("*")) {
            return null;
        }
        String v = ifMatch.replace("W/", "").replace("\"", "").trim();
        try {
            return Integer.valueOf(v);
        } catch (NumberFormatException e) {
            throw new PreconditionFailedException("invalid If-Match value");
        }
    }

    private String hash(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
