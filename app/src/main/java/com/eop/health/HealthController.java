package com.eop.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness endpoint used by the ALB target group health check and by the Phase 1 local proof.
 * Intentionally dependency-free so it answers before any cloud wiring exists.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of(
                "status", "ok",
                "app", "enterprise-onboarding-app",
                "version", "0.0.1"
        );
    }
}
