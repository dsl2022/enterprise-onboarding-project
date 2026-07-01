package com.eop.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Phase 10-1 (ADR-0026): the least-privilege runtime role V10 creates. Proves — against a real Postgres, after
 * Flyway has run V10 — that {@code eop_app} is APPEND-ONLY on the audit log at the privilege level (the
 * engine-level guarantee underneath the V8 immutability trigger) while still being able to write the mutable
 * schemas. Asserted via {@code has_table_privilege} so the test reads the catalog directly rather than relying
 * on crafting valid rows for every table.
 *
 * <p>The migration's {@code GRANT rds_iam} is guarded (IF EXISTS), so V10 — and therefore this test — runs on
 * vanilla Postgres where the RDS-managed {@code rds_iam} role is absent.
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class LeastPrivRuntimeRoleTest {

  @Autowired JdbcTemplate jdbc;

  private boolean priv(String table, String privilege) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject("SELECT has_table_privilege('eop_app', ?, ?)", Boolean.class, table, privilege));
  }

  @Test
  void runtime_role_exists_and_can_login() {
    Boolean canLogin =
        jdbc.queryForObject("SELECT rolcanlogin FROM pg_roles WHERE rolname = 'eop_app'", Boolean.class);
    assertThat(canLogin).as("eop_app must exist and be a LOGIN role").isTrue();
  }

  @Test
  void audit_log_is_append_only_for_the_runtime_role() {
    // The relay appends and GET /audit reads...
    assertThat(priv("audit.audit_events", "INSERT")).as("relay must append").isTrue();
    assertThat(priv("audit.audit_events", "SELECT")).as("GET /audit must read").isTrue();
    // ...but the runtime role CANNOT rewrite history — the whole point of P10-1.
    assertThat(priv("audit.audit_events", "UPDATE")).as("audit UPDATE must be denied at the role level").isFalse();
    assertThat(priv("audit.audit_events", "DELETE")).as("audit DELETE must be denied at the role level").isFalse();
  }

  @Test
  void mutable_schemas_stay_writable_by_the_runtime_role() {
    // Same role, other schemas — proves the split is least-privilege, not blanket read-only.
    assertThat(priv("messaging.outbox", "UPDATE")).as("relay marks published_at/notified_at").isTrue();
    assertThat(priv("request.requests", "INSERT")).as("request engine writes").isTrue();
    assertThat(priv("notify.notifications", "DELETE")).as("notify feed is mutable").isTrue();
  }
}
