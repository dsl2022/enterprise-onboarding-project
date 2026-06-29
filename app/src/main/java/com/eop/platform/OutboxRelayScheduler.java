package com.eop.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link OutboxRelay#runOnce()} on a fixed delay. Gated by {@code eop.relay.scheduler} (off by
 * default, exactly like the provisioning schedulers) so tests drive {@code runOnce()} directly and
 * deterministically; deploy sets {@code EOP_RELAY_SCHEDULER=true}. Unlike provisioning there is NO consent
 * gate — the relay has no external side effect (audit is internal), so it is always-on in every deployed
 * environment. With ≥2 tasks each runs this tick, but the relay's advisory lock means only one does work.
 */
@Component
@ConditionalOnProperty(name = "eop.relay.scheduler", havingValue = "true")
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${eop.relay.poll-ms:2000}")
    public void poll() {
        relay.runOnce();
    }
}
