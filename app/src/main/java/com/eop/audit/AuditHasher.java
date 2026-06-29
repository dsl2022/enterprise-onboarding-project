package com.eop.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single source of truth for the audit hash chain's pre-image + hashing — used by BOTH the
 * {@link AuditProjector} (at insert) and the verifier (at {@code /audit/verify}). Keeping one builder is a
 * correctness requirement: if insert and verify canonicalized differently, verify would falsely report
 * corruption.
 *
 * <p><b>Canonicalization.</b> Keys are sorted recursively ({@code ORDER_MAP_ENTRIES_BY_KEYS}); the
 * pre-image is built field-by-field so it never depends on map iteration order. <b>{@code detail} values
 * MUST be strings/nulls</b> (no floats, no duplicate keys): the value is stored as {@code jsonb} and
 * re-read at verify, and jsonb normalizes numbers and drops duplicate keys — strings survive a
 * store→read→re-canonicalize round-trip byte-for-byte (asserted by a Postgres round-trip test). Every emit
 * site only ever puts strings, so this holds.
 *
 * <p><b>What's hashed.</b> {@code hash = sha256hex(prevHash ‖ '\n' ‖ canonical(preimage))}. The DB-generated
 * {@code seq} is deliberately NOT in the pre-image: it can gap on rollback and isn't known pre-insert, and
 * chain integrity comes entirely from {@code prev_hash} linkage, not seq contiguity.
 */
final class AuditHasher {

    /** prev_hash of the very first row. Fixed constant (64 hex zeros). */
    static final String GENESIS = "0".repeat(64);

    private static final ObjectMapper CANON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private AuditHasher() {}

    /** The ordered content pre-image. Both insert and verify build it identically from these fields. */
    static Map<String, Object> preimage(String sourceEventId, String actor, String effectiveRole,
            String action, String resourceType, String resourceId, String at, Map<String, Object> detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sourceEventId", sourceEventId);
        m.put("actor", actor);
        m.put("effectiveRole", effectiveRole);
        m.put("action", action);
        m.put("resourceType", resourceType);
        m.put("resourceId", resourceId);
        m.put("at", at);
        m.put("detail", detail);
        return m;
    }

    /** The byte-stable canonical form (sorted keys, recursive). Pinned by a golden-vector test. */
    static String canonical(Map<String, Object> preimage) {
        try {
            return CANON.writeValueAsString(preimage);
        } catch (Exception e) {
            throw new IllegalStateException("audit canonicalization failed", e);
        }
    }

    static String hash(String prevHash, Map<String, Object> preimage) {
        String canonical = canonical(preimage);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prevHash.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            md.update(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never on a standard JRE
        }
    }
}
