package com.eop.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The {@code simulate_provisioning} group-membership implementation (default on): returns a synthetic,
 * deterministic grant reference and performs no Graph call, so the access lifecycle reaches GRANTED (and
 * removals complete) with no admin consent. Deterministic, so it's idempotent like the real one. 5b's
 * Graph implementation takes over when {@code eop.provisioning.simulate=false}.
 */
@Component
@ConditionalOnProperty(name = "eop.provisioning.simulate", havingValue = "true", matchIfMissing = true)
public class SimulatedGroupProvisioner implements GroupMembershipProvisioner {

    private static final Logger log = LoggerFactory.getLogger(SimulatedGroupProvisioner.class);

    @Override
    public String addMember(String groupId, String userId) {
        String ref = "sim-grant:" + groupId + ":" + userId;
        log.info("simulate_provisioning: add member {} -> group {} ({})", userId, groupId, ref);
        return ref;
    }

    @Override
    public void removeMember(String groupId, String userId) {
        log.info("simulate_provisioning: remove member {} from group {}", userId, groupId);
    }
}
