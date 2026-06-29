package com.eop.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.TestcontainersConfig;
import com.eop.audit.AuditService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Relay mechanics against a real Postgres: ordered single-writer projection, idempotent replay, leader
 * election by advisory lock, and poison-row blocking. Shares the Testcontainers DB with the rest of the
 * suite, so assertions are delta-based (the audit log is append-only and may already hold rows from other
 * tests) and every method leaves the outbox drainable.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class OutboxRelayTest {

    @Autowired OutboxWriter outbox;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AuditService audit;

    private final CurrentPrincipal auditor =
            new CurrentPrincipal("auditor-1", "Auditor", "a@eop", Set.of(PortalRole.ADMIN), null);

    private void drainAll() {
        for (int i = 0; i < 50 && relay.runOnce() > 0; i++) {
            // loop until the relay reports nothing dispatched (or a safety cap)
        }
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit.audit_events", Long.class);
    }

    private String payload(String id, String actor, String role) {
        return "{\"id\":\"" + id + "\",\"type\":\"ONBOARDING\",\"status\":\"APPROVED\",\"requester\":\"u1\","
                + "\"actor\":\"" + actor + "\",\"effectiveRole\":" + (role == null ? "null" : "\"" + role + "\"")
                + "}";
    }

    @Test
    void projects_in_order_attributes_actor_and_chain_is_valid() {
        drainAll();
        String run = "ord-" + UUID.randomUUID();
        outbox.append("request", run + "-1", "request.approved", payload(run + "-1", "alice", "ADMIN"));
        outbox.append("request", run + "-2", "request.submitted", payload(run + "-2", "bob", "APPLICATION_OWNER"));
        outbox.append("request", run + "-3", "request.active", payload(run + "-3", "system", null));

        drainAll();

        List<String> orderedResourceIds = jdbc.queryForList(
                "SELECT resource_id FROM audit.audit_events WHERE resource_id LIKE ? ORDER BY seq",
                String.class, run + "-%");
        assertThat(orderedResourceIds).containsExactly(run + "-1", run + "-2", run + "-3");

        // actor attribution came from the payload (the relay has no principal context)
        String actor1 = jdbc.queryForObject(
                "SELECT actor FROM audit.audit_events WHERE resource_id = ?", String.class, run + "-1");
        String roleSystem = jdbc.queryForObject(
                "SELECT effective_role FROM audit.audit_events WHERE resource_id = ?", String.class, run + "-3");
        assertThat(actor1).isEqualTo("alice");
        assertThat(roleSystem).isNull(); // system transition → null effective role

        assertThat(audit.verify(auditor).valid()).isTrue();
    }

    @Test
    void redispatch_of_an_already_projected_row_is_idempotent() {
        drainAll();
        String id = "idem-" + UUID.randomUUID();
        outbox.append("request", id, "request.approved", payload(id, "carol", "ADMIN"));
        drainAll();

        long before = auditCount();
        // simulate a crash AFTER the audit insert committed but BEFORE the outbox row was marked published:
        jdbc.update("UPDATE messaging.outbox SET published_at = NULL WHERE aggregate_id = ?", id);
        drainAll(); // re-dispatch → projector hits UNIQUE(source_event_id) → relay swallows the duplicate

        assertThat(auditCount()).isEqualTo(before); // exactly-once effect
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_events WHERE resource_id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM messaging.outbox WHERE aggregate_id = ? AND published_at IS NULL",
                Long.class, id)).isZero(); // re-published
        assertThat(audit.verify(auditor).valid()).isTrue();
    }

    @Test
    void a_task_that_cannot_get_the_leader_lock_does_no_work() throws Exception {
        drainAll();
        String id = "lock-" + UUID.randomUUID();
        outbox.append("request", id, "request.approved", payload(id, "dave", "ADMIN"));
        long before = auditCount();

        try (Connection holder = dataSource.getConnection();
                var ps = holder.prepareStatement("SELECT pg_advisory_lock(?)")) {
            ps.setLong(1, OutboxRelay.LEADER_LOCK_KEY); // another "task" is the leader
            ps.execute();

            assertThat(relay.runOnce()).isZero();           // we are not the leader this tick
            assertThat(auditCount()).isEqualTo(before);     // nothing projected
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM messaging.outbox WHERE aggregate_id = ? AND published_at IS NULL",
                    Long.class, id)).isEqualTo(1L);         // row untouched
        } // holder closed → advisory lock released

        drainAll(); // now we can lead
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_events WHERE resource_id = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test
    void a_poison_row_blocks_rows_behind_it_then_recovers() {
        drainAll();
        String bad = "poison-" + UUID.randomUUID();
        String good = "after-" + UUID.randomUUID();
        // valid jsonb (Postgres accepts it) but a bare scalar, so the projector's object-parse throws —
        // a "poison" event the relay must defer, not a malformed string the outbox would reject up front
        outbox.append("request", bad, "request.approved", "123");
        outbox.append("request", good, "request.approved", payload(good, "erin", "ADMIN"));

        int dispatched = relay.runOnce();
        assertThat(dispatched).isZero(); // stops at the poison row, never skips ahead

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_events WHERE resource_id IN (?, ?)", Long.class, bad, good))
                .isZero();
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM messaging.outbox WHERE aggregate_id = ?", Integer.class, bad);
        assertThat(attempts).isEqualTo(1); // deferred with backoff, not dropped
        assertThat(jdbc.queryForObject(
                "SELECT next_attempt_at IS NOT NULL FROM messaging.outbox WHERE aggregate_id = ?",
                Boolean.class, bad)).isTrue();

        // operator resolves the poison row (here: mark it handled) → the chain advances again
        jdbc.update("UPDATE messaging.outbox SET published_at = now() WHERE aggregate_id = ?", bad);
        drainAll();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_events WHERE resource_id = ?", Long.class, good)).isEqualTo(1L);
        assertThat(audit.verify(auditor).valid()).isTrue();
    }
}
