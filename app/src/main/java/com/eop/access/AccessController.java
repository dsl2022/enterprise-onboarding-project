package com.eop.access;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Access-governance endpoints (frozen contract): catalog (read-only), access-requests, my-access, removal.
 * Idempotency-Key wraps the creating/transition POSTs (replay → original); If-Match carries optimistic
 * concurrency to the engine on decision; ETag on single-request responses. Read ABAC + projection live in
 * {@link AccessService}.
 */
@RestController
@RequestMapping("/api/v1")
public class AccessController {

    private final AccessService access;
    private final IdempotencyService idempotency;
    private final PrincipalFactory principalFactory;
    private final ObjectMapper json;

    public AccessController(AccessService access, IdempotencyService idempotency,
            PrincipalFactory principalFactory, ObjectMapper json) {
        this.access = access;
        this.idempotency = idempotency;
        this.principalFactory = principalFactory;
        this.json = json;
    }

    // ---- catalog ----

    @GetMapping("/catalog")
    public CatalogPage catalog(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String risk,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return access.listCatalog(principal(oidc, session), type, risk, cursor, limit);
    }

    @GetMapping("/catalog/{id}")
    public CatalogResource catalogItem(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable String id) {
        return access.getCatalog(principal(oidc, session), id);
    }

    // ---- access requests ----

    @PostMapping("/access-requests")
    public ResponseEntity<String> create(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestHeader("Idempotency-Key") String key, @RequestBody AccessRequestCreate body) {
        CurrentPrincipal principal = principal(oidc, session);
        return idempotency.execute(principal.realUserId(), "POST /access-requests", key, hash(body), () -> {
            AccessRequestView v = access.create(principal, body);
            return new IdempotencyService.Outcome(201, etag(v.version()), v.request());
        });
    }

    @GetMapping("/access-requests")
    public AccessRequestPage list(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return access.list(principal(oidc, session), status, type, cursor, limit);
    }

    @GetMapping("/access-requests/{id}")
    public ResponseEntity<AccessRequest> get(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id) {
        AccessRequestView v = access.get(principal(oidc, session), id);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag(v.version())).body(v.request());
    }

    @PostMapping("/access-requests/{id}/decision")
    public ResponseEntity<String> decision(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch, @RequestBody DecisionRequest body) {
        CurrentPrincipal principal = principal(oidc, session);
        Integer ifMatchVersion = parseIfMatch(ifMatch);
        return idempotency.execute(principal.realUserId(), "POST /access-requests/" + id + "/decision", key,
                hash(body), () -> {
                    AccessRequestView v = access.decide(principal, id, body, ifMatchVersion);
                    return new IdempotencyService.Outcome(200, etag(v.version()), v.request());
                });
    }

    // ---- my-access + removal ----

    @GetMapping("/my-access")
    public List<MyAccessItem> myAccess(@AuthenticationPrincipal OidcUser oidc, HttpSession session) {
        return access.myAccess(principal(oidc, session));
    }

    @PostMapping("/my-access/{resourceId}/removal")
    public ResponseEntity<String> removal(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable String resourceId, @RequestHeader("Idempotency-Key") String key) {
        CurrentPrincipal principal = principal(oidc, session);
        return idempotency.execute(principal.realUserId(), "POST /my-access/" + resourceId + "/removal", key, "",
                () -> {
                    AccessRequestView v = access.requestRemoval(principal, resourceId);
                    return new IdempotencyService.Outcome(201, etag(v.version()), v.request());
                });
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
