package com.eop.platform;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The single, leader-elected reader of {@code messaging.outbox}. Each tick it claims unpublished rows in
 * {@code occurred_at} order and dispatches them to every {@link OutboxEventHandler} (audit in 6a; notify in
 * 6b), then marks each row published.
 *
 * <p><b>Why a single leader (not parallel consumers).</b> The audit hash chain is linear — {@code hash =
 * H(prevHash ‖ row)} — so two appenders running concurrently would fork it. With N Fargate tasks, exactly
 * one must relay. We elect by a Postgres <b>session-level advisory lock</b> held for the whole tick on a
 * <b>dedicated connection</b> (acquire → work → release-in-finally; the connection close also releases it,
 * so a crashed leader fails over cleanly next tick). A task that can't get the lock does nothing this tick.
 * {@code FOR UPDATE SKIP LOCKED} row-claiming was rejected for audit: parallel claimers can't produce an
 * ordered chain. (SKIP LOCKED is the right tool for 6b's order-independent notification fan-out.)
 *
 * <p><b>Crash-safety / exactly-once effect.</b> The handler insert and the {@code published_at} update are
 * NOT one transaction across a crash, so a row can be re-dispatched after its projection already committed.
 * Handlers make that harmless by being idempotent (audit: {@code UNIQUE(source_event_id)} → the duplicate
 * insert rolls back its OWN transaction and surfaces as {@code DuplicateKeyException}, which we swallow
 * per-handler). Because each handler runs in its own transaction and {@code markPublished}/{@code backoff}
 * are autonomous (no enclosing transaction on this bean), the constraint-violation never poisons a shared
 * transaction — the architect's savepoint requirement is satisfied structurally.
 *
 * <p><b>Ordering vs. poison rows.</b> A transient handler failure defers that row (exponential backoff) and
 * <b>stops the tick</b> — we never project rows that sit behind a stuck one, because audit must not skip. A
 * permanently-stuck row therefore halts the whole chain, which for an audit log is correct but is a
 * compliance hazard, so it is logged loudly once it crosses {@link #POISON_ATTEMPTS} (full dead-lettering is
 * a 6b concern).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Constant key for the relay-leader advisory lock (arbitrary but fixed across all tasks). */
    static final long LEADER_LOCK_KEY = 0x6000_0001L;
    /** Attempts after which a stuck row is screamed about (it is blocking the entire chain). */
    static final int POISON_ATTEMPTS = 10;

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final List<OutboxEventHandler> handlers;
    private final int batchSize;
    private final int backoffCapSeconds;

    public OutboxRelay(DataSource dataSource, JdbcTemplate jdbc, List<OutboxEventHandler> handlers,
            @Value("${eop.relay.batch-size:100}") int batchSize,
            @Value("${eop.provisioning.backoff-cap-seconds:3600}") int backoffCapSeconds) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.handlers = handlers;
        this.batchSize = batchSize;
        this.backoffCapSeconds = backoffCapSeconds;
    }

    /**
     * One poll tick. Returns the number of rows dispatched (0 if this task isn't the leader this tick).
     * Safe to call from N tasks concurrently — the advisory lock guarantees at most one does work.
     */
    public int runOnce() {
        try (Connection lockConn = dataSource.getConnection()) {
            if (!tryLeaderLock(lockConn)) {
                return 0; // another task is the relay leader this tick
            }
            try {
                return drain();
            } finally {
                releaseLeaderLock(lockConn);
            }
        } catch (SQLException e) {
            log.warn("relay tick aborted on a lock/connection error; will retry next tick", e);
            return 0;
        }
    }

    private int drain() {
        List<OutboxRecord> batch = claim();
        int dispatched = 0;
        for (OutboxRecord rec : batch) {
            try {
                dispatch(rec);
                markPublished(rec.id());
                dispatched++;
            } catch (RuntimeException ex) {
                backoff(rec, ex);
                break; // preserve ordering — never project rows behind a stuck one
            }
        }
        return dispatched;
    }

    /** Each handler runs in its own transaction; an "already projected" duplicate is fine, anything else propagates. */
    private void dispatch(OutboxRecord rec) {
        for (OutboxEventHandler handler : handlers) {
            try {
                handler.handle(rec);
            } catch (DuplicateKeyException alreadyProjected) {
                // idempotent replay for THIS handler (e.g. crash between projection commit and markPublished)
            }
        }
    }

    private List<OutboxRecord> claim() {
        return jdbc.query(
                "SELECT id, aggregate_type, aggregate_id, event_type, payload, occurred_at, attempts "
                        + "FROM messaging.outbox WHERE published_at IS NULL "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at <= now()) "
                        + "ORDER BY occurred_at, id LIMIT ?",
                (rs, n) -> new OutboxRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("attempts")),
                batchSize);
    }

    private void markPublished(UUID id) {
        jdbc.update("UPDATE messaging.outbox SET published_at = now() WHERE id = ?", id);
    }

    private void backoff(OutboxRecord rec, RuntimeException cause) {
        int attempts = rec.attempts() + 1;
        long delaySeconds = Math.min((long) Math.pow(2, Math.min(attempts, 12)), backoffCapSeconds);
        jdbc.update("UPDATE messaging.outbox SET attempts = attempts + 1, "
                + "next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?", (double) delaySeconds, rec.id());
        if (attempts >= POISON_ATTEMPTS) {
            log.error("OUTBOX POISON: event {} ({}/{}) stuck after {} attempts — the audit chain is BLOCKED "
                    + "behind it and will not advance until it is resolved", rec.id(), rec.aggregateType(),
                    rec.eventType(), attempts, cause);
        } else {
            log.warn("relay deferring event {} ({}) — attempt {}, retry in {}s", rec.id(), rec.eventType(),
                    attempts, delaySeconds, cause);
        }
    }

    private boolean tryLeaderLock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, LEADER_LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void releaseLeaderLock(Connection c) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, LEADER_LOCK_KEY);
            ps.execute();
        } catch (SQLException e) {
            // best-effort: closing the dedicated connection releases the session lock regardless
            log.debug("advisory unlock failed (connection close will release it)", e);
        }
    }
}
