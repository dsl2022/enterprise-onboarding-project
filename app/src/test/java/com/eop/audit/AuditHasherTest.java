package com.eop.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden-vector + determinism guard for the audit canonicalization/hash (the in-memory half of the
 * round-trip correctness requirement; the Postgres jsonb round-trip is asserted in {@link AuditEndpointTest}).
 * If anyone changes how the pre-image canonicalizes, the pinned string below breaks the build — because that
 * change would silently invalidate {@code /audit/verify} for every already-chained row.
 */
class AuditHasherTest {

    private Map<String, Object> detail(String... kvOrderToProveItDoesntMatter) {
        Map<String, Object> d = new LinkedHashMap<>();
        for (int i = 0; i < kvOrderToProveItDoesntMatter.length; i += 2) {
            d.put(kvOrderToProveItDoesntMatter[i], kvOrderToProveItDoesntMatter[i + 1]);
        }
        return d;
    }

    private Map<String, Object> goldenPreimage(Map<String, Object> detail) {
        return AuditHasher.preimage("11111111-1111-1111-1111-111111111111", "alice", "ADMIN",
                "request.approved", "request", "req-1", "2026-06-29T12:00:00Z", detail);
    }

    @Test
    void canonical_form_is_pinned_and_sorted_recursively() {
        String canonical = AuditHasher.canonical(goldenPreimage(detail("type", "ONBOARDING", "status", "APPROVED")));
        // keys sorted alphabetically at every level; no insignificant whitespace
        assertThat(canonical).isEqualTo("{\"action\":\"request.approved\",\"actor\":\"alice\","
                + "\"at\":\"2026-06-29T12:00:00Z\",\"detail\":{\"status\":\"APPROVED\",\"type\":\"ONBOARDING\"},"
                + "\"effectiveRole\":\"ADMIN\",\"resourceId\":\"req-1\",\"resourceType\":\"request\","
                + "\"sourceEventId\":\"11111111-1111-1111-1111-111111111111\"}");
    }

    @Test
    void canonical_is_independent_of_detail_insertion_order() {
        String a = AuditHasher.canonical(goldenPreimage(detail("type", "ONBOARDING", "status", "APPROVED")));
        String b = AuditHasher.canonical(goldenPreimage(detail("status", "APPROVED", "type", "ONBOARDING")));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void hash_is_deterministic_hex_and_chains_on_prevhash() {
        Map<String, Object> p = goldenPreimage(detail("status", "APPROVED"));
        String h1 = AuditHasher.hash(AuditHasher.GENESIS, p);
        String h2 = AuditHasher.hash(AuditHasher.GENESIS, p);
        assertThat(h1).isEqualTo(h2).hasSize(64).matches("[0-9a-f]{64}");
        // a different prev_hash → a different chain hash (so reordering/forking is detectable)
        assertThat(AuditHasher.hash("deadbeef", p)).isNotEqualTo(h1);
    }

    @Test
    void null_effective_role_is_stable() {
        Map<String, Object> p = AuditHasher.preimage("s", "system", null, "request.provisioning", "request",
                "r", "2026-06-29T12:00:00Z", detail());
        assertThat(AuditHasher.canonical(p)).contains("\"effectiveRole\":null");
        assertThat(AuditHasher.hash(AuditHasher.GENESIS, p)).hasSize(64);
    }
}
