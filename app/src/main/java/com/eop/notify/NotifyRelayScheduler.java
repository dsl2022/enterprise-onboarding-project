package com.eop.notify;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link NotifyRelay#runOnce()} on a fixed delay. Gated by {@code eop.notify.scheduler} (off by
 * default — tests drive {@code runOnce()} directly), set on in deploy via {@code EOP_NOTIFY_SCHEDULER=true}.
 * Like the audit relay there is no consent gate (the in-app feed has no external side effect). With ≥2 tasks
 * each runs the tick; SKIP LOCKED means they fan out over disjoint rows rather than contending.
 */
@Component
@ConditionalOnProperty(name = "eop.notify.scheduler", havingValue = "true")
public class NotifyRelayScheduler {

    private final NotifyRelay relay;

    public NotifyRelayScheduler(NotifyRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${eop.notify.poll-ms:2000}")
    public void poll() {
        relay.runOnce();
    }
}
