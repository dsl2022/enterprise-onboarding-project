package com.eop.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.eop.TestcontainersConfig;
import com.eop.audit.AuditService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.PortalRole;
import com.eop.platform.OutboxRelay;
import com.eop.platform.OutboxWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The notify consumer against real Postgres: recipient fan-out, self-suppression, causal (occurred_at)
 * ordering, idempotent replay, and — the property the whole separate-consumer design exists to guarantee —
 * <b>decoupling from the audit relay in both directions</b>.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class NotifyRelayTest {

    @Autowired OutboxWriter outbox;
    @Autowired NotifyRelay notifyRelay;
    @Autowired OutboxRelay auditRelay;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    @SpyBean NotifyProjector projector; // spied so one test can force notify to fail

    private final CurrentPrincipal auditor =
            new CurrentPrincipal("auditor-n", "Auditor", "a@eop", Set.of(PortalRole.ADMIN), null);

    private void drainNotify() {
        for (int i = 0; i < 50 && notifyRelay.runOnce() > 0; i++) {
            // drain
        }
    }

    private void drainAudit() {
        for (int i = 0; i < 50 && auditRelay.runOnce() > 0; i++) {
            // drain
        }
    }

    private String reqPayload(String rid, String requester, String actor) {
        return "{\"id\":\"" + rid + "\",\"type\":\"ONBOARDING\",\"status\":\"APPROVED\",\"requester\":\""
                + requester + "\",\"actor\":\"" + actor + "\",\"effectiveRole\":\"ADMIN\"}";
    }

    private Long notifCount(String recipient, String resourceRef) {
        return jdbc.queryForObject("SELECT count(*) FROM notify.notifications WHERE recipient = ? AND resource_ref = ?",
                Long.class, recipient, resourceRef);
    }

    @Test
    void fans_out_to_requester_with_event_time_and_is_idempotent() {
        String rid = "napprove-" + UUID.randomUUID();
        outbox.append("request", rid, "request.approved", reqPayload(rid, "alice", "ops-bob"));
        drainNotify();

        // exactly one notification, to the requester, carrying the event's occurred_at (NOT insert time)
        Instant outboxAt = jdbc.queryForObject(
                "SELECT occurred_at FROM messaging.outbox WHERE aggregate_id = ?", OffsetDateTime.class, rid).toInstant();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notify.notifications WHERE resource_ref = ?",
                Long.class, rid)).isEqualTo(1L);
        assertThat(jdbc.queryForMap("SELECT recipient, type, read, created_at FROM notify.notifications WHERE resource_ref = ?", rid))
                .containsEntry("recipient", "alice")
                .containsEntry("type", "request.approved")
                .containsEntry("read", false);
        Instant notifAt = jdbc.queryForObject(
                "SELECT created_at FROM notify.notifications WHERE resource_ref = ?", OffsetDateTime.class, rid).toInstant();
        assertThat(notifAt).isEqualTo(outboxAt); // catch A — causal feed order

        // re-dispatch (crash simulation): null out notified_at, re-drain → still exactly one (ON CONFLICT)
        jdbc.update("UPDATE messaging.outbox SET notified_at = NULL WHERE aggregate_id = ?", rid);
        drainNotify();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notify.notifications WHERE resource_ref = ?",
                Long.class, rid)).isEqualTo(1L);
    }

    @Test
    void self_actions_are_suppressed() {
        String r1 = "nself-req-" + UUID.randomUUID();
        outbox.append("request", r1, "request.approved", reqPayload(r1, "carol", "carol")); // requester == actor
        String r2 = "nself-team-" + UUID.randomUUID();
        outbox.append("team", r2, "team.member.added",
                "{\"teamId\":\"" + r2 + "\",\"userId\":\"dave\",\"actorId\":\"dave\",\"actor\":\"dave\"}"); // added self
        drainNotify();

        assertThat(notifCount("carol", r1)).isZero();
        assertThat(notifCount("dave", r2)).isZero();
    }

    @Test
    void non_notifiable_events_produce_nothing_and_team_add_notifies_member() {
        String created = "ncreated-" + UUID.randomUUID();
        outbox.append("request", created, "request.created", reqPayload(created, "erin", "erin"));
        // provisioning_failed is intentionally NOT notifiable — it auto-retries (no terminal FAILED state),
        // so notifying would be repeat noise + a confusing problem-then-success sequence (architect note).
        String failed = "nfail-" + UUID.randomUUID();
        outbox.append("request", failed, "request.provisioning_failed", reqPayload(failed, "erin", "system"));
        String added = "nadd-" + UUID.randomUUID();
        outbox.append("team", added, "team.member.added",
                "{\"teamId\":\"" + added + "\",\"userId\":\"frank\",\"actorId\":\"owner-z\",\"actor\":\"owner-z\"}");
        drainNotify();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notify.notifications WHERE resource_ref = ?",
                Long.class, created)).isZero();                 // request.created is not notifiable
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notify.notifications WHERE resource_ref = ?",
                Long.class, failed)).isZero();                  // request.provisioning_failed is not notifiable
        assertThat(notifCount("frank", added)).isEqualTo(1L);   // the added member is notified
    }

    @Test
    void decoupling_notify_failure_does_not_block_audit() {
        doThrow(new IllegalStateException("notify boom")).when(projector).project(any());
        String rid = "ndecouple-" + UUID.randomUUID();
        outbox.append("request", rid, "request.approved", reqPayload(rid, "grace", "ops-x"));

        drainNotify(); // notify throws → row deferred, NO notification
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notify.notifications WHERE resource_ref = ?",
                Long.class, rid)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT notify_attempts FROM messaging.outbox WHERE aggregate_id = ?", Integer.class, rid))
                .isPositive(); // deferred with backoff
        assertThat(jdbc.queryForObject(
                "SELECT notified_at IS NULL FROM messaging.outbox WHERE aggregate_id = ?", Boolean.class, rid)).isTrue();

        // ...meanwhile AUDIT projects the very same event and the chain stays valid — the decoupling guarantee
        drainAudit();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit.audit_events WHERE resource_id = ?",
                Long.class, rid)).isEqualTo(1L);
        assertThat(audit.verify(auditor).valid()).isTrue();
    }

    @Test
    void decoupling_notify_progresses_without_audit() {
        String rid = "nahead-" + UUID.randomUUID();
        outbox.append("request", rid, "request.granted", reqPayload(rid, "heidi", "ops-y"));

        drainNotify(); // run ONLY notify — never the audit relay for this row

        assertThat(notifCount("heidi", rid)).isEqualTo(1L);            // notify advanced...
        assertThat(jdbc.queryForObject(
                "SELECT published_at IS NULL FROM messaging.outbox WHERE aggregate_id = ?", Boolean.class, rid))
                .isTrue();                                            // ...while audit hasn't processed it
    }
}
