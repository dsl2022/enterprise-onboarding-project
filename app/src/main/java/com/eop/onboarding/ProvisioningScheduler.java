package com.eop.onboarding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link ProvisioningService#runOnce()} on a fixed delay. Off by default (so tests don't auto-run
 * the worker — they call {@code runOnce()} directly); deployment enables it with
 * {@code eop.provisioning.onboarding.scheduler=true}. Per-vertical (separate from the access scheduler) so
 * the two verticals activate independently. Requires {@code @EnableScheduling} (on the app).
 */
@Component
@ConditionalOnProperty(name = "eop.provisioning.onboarding.scheduler", havingValue = "true")
public class ProvisioningScheduler {

    private final ProvisioningService provisioning;

    public ProvisioningScheduler(ProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Scheduled(fixedDelayString = "${eop.provisioning.poll-ms:10000}")
    public void poll() {
        provisioning.runOnce();
    }
}
