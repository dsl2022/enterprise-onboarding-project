package com.eop.access;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link AccessProvisioningService#runOnce()} on a fixed delay. Off by default (tests call
 * {@code runOnce()} directly); deployment enables it with {@code eop.provisioning.access.scheduler=true}.
 * Per-vertical (separate from the onboarding scheduler) so the two verticals activate independently.
 */
@Component
@ConditionalOnProperty(name = "eop.provisioning.access.scheduler", havingValue = "true")
public class AccessProvisioningScheduler {

    private final AccessProvisioningService provisioning;

    public AccessProvisioningScheduler(AccessProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Scheduled(fixedDelayString = "${eop.provisioning.poll-ms:10000}")
    public void poll() {
        provisioning.runOnce();
    }
}
