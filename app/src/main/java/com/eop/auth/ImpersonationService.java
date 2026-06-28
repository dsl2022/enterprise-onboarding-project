package com.eop.auth;

import com.eop.authz.PortalRole;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Holds the impersonation overlay in the server-side session (Redis-backed since Phase 2, so it is
 * consistent across all Fargate tasks). The overlay is ephemeral session state; the durable record of
 * starting/stopping impersonation is the audit event (Phase 6). Identity never changes — only the
 * effective role does.
 */
@Service
public class ImpersonationService {

    static final String SESSION_KEY = "eop.impersonatedRole";

    public Optional<PortalRole> current(HttpSession session) {
        Object value = session.getAttribute(SESSION_KEY);
        return value instanceof PortalRole role ? Optional.of(role) : Optional.empty();
    }

    public void start(HttpSession session, PortalRole role) {
        session.setAttribute(SESSION_KEY, role);
    }

    public void stop(HttpSession session) {
        session.removeAttribute(SESSION_KEY);
    }
}
