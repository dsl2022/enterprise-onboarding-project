package com.eop.review;

import com.eop.authz.AuthorizationService;
import com.eop.authz.Permission;
import com.eop.platform.PrincipalFactory;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unified review queue (frozen contract). Gated to {@code review.read} — reviewers only (SSO Operations,
 * Admin, Auditor, Super Admin); Application Owners and Read-Only get 403.
 */
@RestController
@RequestMapping("/api/v1/review-queue")
public class ReviewController {

    private final ReviewService reviews;
    private final PrincipalFactory principalFactory;
    private final AuthorizationService authz;

    public ReviewController(ReviewService reviews, PrincipalFactory principalFactory, AuthorizationService authz) {
        this.reviews = reviews;
        this.principalFactory = principalFactory;
        this.authz = authz;
    }

    @GetMapping
    public ReviewQueuePage queue(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        if (oidc == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        authz.require(principalFactory.from(oidc, session), Permission.REVIEW_READ); // 403 for non-reviewers
        return reviews.queue(type, cursor, limit);
    }
}
