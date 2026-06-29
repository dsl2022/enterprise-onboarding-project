package com.eop.notify;

import com.eop.authz.CurrentPrincipal;
import com.eop.platform.PrincipalFactory;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Notification endpoints (frozen contract): the caller's own feed + mark-read. All scoped to the real
 * principal in {@link NotificationService}. Notifications are derived by the relay — there is no create
 * endpoint.
 */
@RestController
@RequestMapping("/api/v1")
public class NotificationController {

    private final NotificationService notifications;
    private final PrincipalFactory principalFactory;

    public NotificationController(NotificationService notifications, PrincipalFactory principalFactory) {
        this.notifications = notifications;
        this.principalFactory = principalFactory;
    }

    @GetMapping("/notifications")
    public NotificationFeed feed(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int limit) {
        return notifications.feed(principal(oidc, session), cursor, limit);
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal OidcUser oidc, HttpSession session,
            @PathVariable UUID id) {
        notifications.markRead(principal(oidc, session), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    public ResponseEntity<Void> readAll(@AuthenticationPrincipal OidcUser oidc, HttpSession session) {
        notifications.markAllRead(principal(oidc, session));
        return ResponseEntity.noContent().build();
    }

    private CurrentPrincipal principal(OidcUser oidc, HttpSession session) {
        if (oidc == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principalFactory.from(oidc, session);
    }
}
