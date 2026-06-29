package com.eop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * v0 cross-cloud walking skeleton.
 *
 * <p>Package map (filled in over the phased plan):
 * <ul>
 *   <li>{@code auth}  — Flow 1: BFF OIDC Auth Code + PKCE against Entra; server-side session. (Phase 4)</li>
 *   <li>{@code wif}   — Flow 2a/3b: mint the self-issued workload JWT and exchange it at Entra. (Phase 5)</li>
 *   <li>{@code graph} — Flow 2c: call Microsoft Graph (paging + 429 backoff). (Phase 5)</li>
 *   <li>{@code health}— {@code /healthz} liveness, working in Phase 1.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling // scheduling infra; the provisioning poller bean is gated by eop.provisioning.scheduler
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
