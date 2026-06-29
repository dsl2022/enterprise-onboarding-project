package com.eop.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eop.TestcontainersConfig;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.ForbiddenException;
import com.eop.authz.PortalRole;
import com.eop.platform.OutboxRelay;
import com.eop.platform.OutboxWriter;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The audit read/verify surface end-to-end against a real Postgres: chain verification (valid + tampered),
 * the DB-level immutability trigger, the jsonb-detail round-trip (store → read → re-canonicalize → re-hash),
 * permission gating, and the GET /audit filters.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class AuditEndpointTest {

    @Autowired OutboxWriter outbox;
    @Autowired OutboxRelay relay;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    private final CurrentPrincipal auditor =
            new CurrentPrincipal("auditor-2", "Auditor", "a@eop", Set.of(PortalRole.ADMIN), null);
    private final CurrentPrincipal owner =
            new CurrentPrincipal("owner-2", "Owner", "o@eop", Set.of(PortalRole.APPLICATION_OWNER), null);

    private void drainAll() {
        for (int i = 0; i < 50 && relay.runOnce() > 0; i++) {
            // drain
        }
    }

    private String seedAndAudit(String resourceId, String actor, String role) {
        // detail with keys deliberately out of sorted order to exercise the canonical round-trip
        outbox.append("request", resourceId, "request.approved",
                "{\"type\":\"ONBOARDING\",\"status\":\"APPROVED\",\"requester\":\"u1\",\"actor\":\"" + actor
                        + "\",\"effectiveRole\":\"" + role + "\"}");
        drainAll();
        return resourceId;
    }

    @Test
    void verify_is_valid_for_a_faithful_chain_including_jsonb_roundtrip() {
        seedAndAudit("verify-" + UUID.randomUUID(), "frank", "ADMIN");
        AuditVerifyResult result = audit.verify(auditor);
        assertThat(result.valid()).isTrue();
        assertThat(result.brokenAt()).isNull();
        assertThat(result.checkedThrough()).isPositive();
    }

    @Test
    void verify_detects_tampering_and_reports_the_broken_seq() {
        String rid = seedAndAudit("tamper-" + UUID.randomUUID(), "grace", "ADMIN");
        Long seq = jdbc.queryForObject(
                "SELECT seq FROM audit.audit_events WHERE resource_id = ?", Long.class, rid);

        // a DBA bypassing the trigger to rewrite history (the only way to mutate the row at all)
        jdbc.execute("ALTER TABLE audit.audit_events DISABLE TRIGGER audit_events_immutable");
        try {
            jdbc.update("UPDATE audit.audit_events SET actor = 'FORGED' WHERE resource_id = ?", rid);
            AuditVerifyResult broken = audit.verify(auditor);
            assertThat(broken.valid()).isFalse();
            assertThat(broken.brokenAt()).isEqualTo(seq); // content no longer matches its hash

            jdbc.update("UPDATE audit.audit_events SET actor = 'grace' WHERE resource_id = ?", rid); // restore
        } finally {
            jdbc.execute("ALTER TABLE audit.audit_events ENABLE TRIGGER audit_events_immutable");
        }
        assertThat(audit.verify(auditor).valid()).isTrue(); // chain healed, suite isolation preserved
    }

    @Test
    void audit_rows_are_immutable_update_and_delete_are_denied() {
        String rid = seedAndAudit("immut-" + UUID.randomUUID(), "heidi", "ADMIN");
        assertThatThrownBy(() ->
                jdbc.update("UPDATE audit.audit_events SET actor = 'x' WHERE resource_id = ?", rid))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() ->
                jdbc.update("DELETE FROM audit.audit_events WHERE resource_id = ?", rid))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void numeric_detail_values_are_coerced_to_strings_so_verify_stays_stable() {
        // a future emit site adds a numeric (and a float) field — jsonb could normalize these and break the
        // round-trip, but the projector coerces every detail value to a string, so verify stays valid
        String rid = "num-" + UUID.randomUUID();
        outbox.append("request", rid, "request.approved",
                "{\"actor\":\"judy\",\"effectiveRole\":\"ADMIN\",\"count\":5,\"ratio\":1.10}");
        drainAll();

        assertThat(audit.verify(auditor).valid()).isTrue();
        AuditPage page = audit.query(auditor, "judy", null, null, null, null, null, 20);
        assertThat(page.items()).anySatisfy(e -> {
            assertThat(e.detail().get("count")).isEqualTo("5");                 // number → string
            assertThat(e.detail().get("ratio")).isInstanceOf(String.class);    // float → string (no jsonb normalization risk)
        });
    }

    @Test
    void query_filters_by_actor_and_requires_audit_read() {
        String actor = "ivan-" + UUID.randomUUID();
        seedAndAudit("q-" + UUID.randomUUID(), actor, "ADMIN");

        AuditPage page = audit.query(auditor, actor, null, null, null, null, null, 20);
        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).allSatisfy(e -> assertThat(e.actor()).isEqualTo(actor));

        // a non-privileged role is denied on both endpoints
        assertThatThrownBy(() -> audit.query(owner, null, null, null, null, null, null, 20))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> audit.verify(owner)).isInstanceOf(ForbiddenException.class);
    }
}
