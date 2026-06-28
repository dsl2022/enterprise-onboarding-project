package com.eop.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.eop.TestcontainersConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the Phase 2 persistence substrate boots end to end against a real Postgres: Flyway applies the
 * baseline, pgvector is usable, and the per-module schemas exist (the boundary the later modules rely on).
 */
@SpringBootTest
@ActiveProfiles("data")
@Import(TestcontainersConfig.class)
class DataLayerTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flyway_baseline_applied() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isNotNull().isGreaterThanOrEqualTo(1);
    }

    @Test
    void pgvector_extension_is_usable() {
        // Distance between identical vectors is 0 — only works if the `vector` type/extension exists.
        Double distance = jdbc.queryForObject(
                "SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector", Double.class);
        assertThat(distance).isEqualTo(0.0d);
    }

    @Test
    void per_module_schemas_exist() {
        List<String> schemas = jdbc.queryForList(
                "SELECT schema_name FROM information_schema.schemata", String.class);
        assertThat(schemas).contains(
                "request", "onboarding", "registry", "access",
                "directory", "audit", "notify", "assistant");
    }
}
