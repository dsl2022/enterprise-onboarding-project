package com.eop.directory;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@code simulate_provisioning} implementation (default on): returns a synthetic, deterministic
 * client id so the full onboarding lifecycle reaches ACTIVE without any Graph call or admin consent.
 * Deterministic by request id, so it's idempotent like the real one. 4b's Graph implementation takes
 * over when {@code eop.provisioning.simulate=false}.
 */
@Component
@ConditionalOnProperty(name = "eop.provisioning.simulate", havingValue = "true", matchIfMissing = true)
public class SimulatedProvisioner implements AppRegistrationProvisioner {

    private static final Logger log = LoggerFactory.getLogger(SimulatedProvisioner.class);

    @Override
    public String provision(UUID requestId, String displayName) {
        String clientId = "sim-" + requestId;
        log.info("simulate_provisioning: registration '{}' -> clientId {}", displayName, clientId);
        return clientId;
    }
}
