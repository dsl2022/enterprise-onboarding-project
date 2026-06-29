package com.eop.audit;

import com.eop.authz.CurrentPrincipal;
import com.eop.platform.PrincipalFactory;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Audit endpoints (frozen contract): {@code GET /audit} (filtered, cursor-paged) and {@code GET
 * /audit/verify} (recompute the hash chain). Both require {@code audit.read} — enforced in {@link
 * AuditService}. The chain itself is built by the relay (write side), never by these reads.
 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final AuditService audit;
    private final PrincipalFactory principalFactory;

    public AuditController(AuditService audit, PrincipalFactory principalFactory) {
        this.audit = audit;
        this.principalFactory = principalFactory;
    }

    @GetMapping("/audit")
    public AuditPage query(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return audit.query(principal(oidc, session), actor, type, resource, instant(from), instant(to),
                cursor, limit);
    }

    @GetMapping("/audit/verify")
    public AuditVerifyResult verify(@AuthenticationPrincipal OidcUser oidc, HttpSession session) {
        return audit.verify(principal(oidc, session));
    }

    private CurrentPrincipal principal(OidcUser oidc, HttpSession session) {
        if (oidc == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principalFactory.from(oidc, session);
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
