package com.eop.notify;

import com.eop.platform.OutboxRecord;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SECOND outbox consumer (the first is the audit {@code OutboxRelay}). It projects each event into the
 * in-app feed and is, by design, fully decoupled from audit:
 *
 * <ul>
 *   <li><b>No ordering, no leader.</b> Notifications have no chain, so this fans out with {@code FOR UPDATE
 *       SKIP LOCKED} — N tasks claim disjoint rows in parallel. No advisory lock.</li>
 *   <li><b>Its own markers.</b> It claims on {@code notified_at IS NULL} and backs off via
 *       {@code notify_attempts}/{@code notify_next_attempt_at} — distinct from audit's columns, so the two
 *       consumers never collide. A row is fully consumed only when both {@code published_at} (audit) and
 *       {@code notified_at} (notify) are set.</li>
 *   <li><b>Claim → project → mark, in ONE transaction per row.</b> SKIP LOCKED makes that safe (disjoint
 *       claims) and removes the redispatch window; {@code UNIQUE(source_event_id, recipient)} remains as
 *       defense for a crash before commit. (Audit needed a split-tx dance only because of its chain.)</li>
 *   <li><b>A poison row never blocks others.</b> Order doesn't matter, so a failed row is deferred (backoff)
 *       and we move on — unlike audit, which must stop to preserve the chain.</li>
 * </ul>
 *
 * <p>The decoupling is the whole point: an SES/notify failure (6b+) must never freeze the tamper-evident
 * audit chain, and a wedged audit relay must never freeze notifications.
 */
@Component
public class NotifyRelay {

    private static final Logger log = LoggerFactory.getLogger(NotifyRelay.class);
    static final int POISON_ATTEMPTS = 10;

    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final NotifyProjector projector;
    private final int batchSize;
    private final int backoffCapSeconds;

    public NotifyRelay(PlatformTransactionManager txManager, JdbcTemplate jdbc, NotifyProjector projector,
            @Value("${eop.notify.batch-size:200}") int batchSize,
            @Value("${eop.provisioning.backoff-cap-seconds:3600}") int backoffCapSeconds) {
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.projector = projector;
        this.batchSize = batchSize;
        this.backoffCapSeconds = backoffCapSeconds;
    }

    /** One poll tick. Returns the number of rows projected to the feed. Safe to run from N tasks (SKIP LOCKED). */
    public int runOnce() {
        int processed = 0;
        for (int i = 0; i < batchSize; i++) {
            var holder = new Object() {
                UUID id;
            };
            try {
                Boolean did = tx.execute(status -> {
                    OutboxRecord rec = claimOne();
                    if (rec == null) {
                        return false; // nothing claimable this tick
                    }
                    holder.id = rec.id();
                    projector.project(rec);
                    jdbc.update("UPDATE messaging.outbox SET notified_at = now() WHERE id = ?", rec.id());
                    return true;
                });
                if (!Boolean.TRUE.equals(did)) {
                    break;
                }
                processed++;
            } catch (RuntimeException ex) {
                // the per-row tx rolled back (row unclaimed again); defer it and CONTINUE — order-independent
                if (holder.id != null) {
                    backoff(holder.id, ex);
                }
            }
        }
        return processed;
    }

    /** One unconsumed row, locked for this tx; concurrent consumers skip it (no blocking, no double-send). */
    private OutboxRecord claimOne() {
        return jdbc.query(
                "SELECT id, aggregate_type, aggregate_id, event_type, payload, occurred_at, notify_attempts "
                        + "FROM messaging.outbox WHERE notified_at IS NULL "
                        + "AND (notify_next_attempt_at IS NULL OR notify_next_attempt_at <= now()) "
                        + "ORDER BY occurred_at, id FOR UPDATE SKIP LOCKED LIMIT 1",
                rs -> !rs.next() ? null : new OutboxRecord(
                        rs.getObject("id", UUID.class), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("notify_attempts")));
    }

    private void backoff(UUID id, RuntimeException cause) {
        int attempts = jdbc.queryForObject(
                "SELECT notify_attempts FROM messaging.outbox WHERE id = ?", Integer.class, id) + 1;
        long delaySeconds = Math.min((long) Math.pow(2, Math.min(attempts, 12)), backoffCapSeconds);
        jdbc.update("UPDATE messaging.outbox SET notify_attempts = notify_attempts + 1, "
                + "notify_next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?",
                (double) delaySeconds, id);
        if (attempts >= POISON_ATTEMPTS) {
            log.error("NOTIFY POISON: event {} stuck after {} attempts (audit is unaffected)", id, attempts, cause);
        } else {
            log.warn("notify deferring event {} — attempt {}, retry in {}s", id, attempts, delaySeconds, cause);
        }
    }
}
