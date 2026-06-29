package com.eop.audit;

import com.eop.platform.OutboxEventHandler;
import com.eop.platform.OutboxRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects each {@code messaging.outbox} row into one append-only {@code audit.audit_events} row, extending
 * the hash chain. Invoked ONLY by the single-leader {@link com.eop.platform.OutboxRelay}, so the read of the
 * chain tail (prev hash) and the insert are race-free without extra locking — there is exactly one appender.
 *
 * <p>Idempotent: {@code source_event_id} (= the outbox row id) is UNIQUE, so a re-dispatch after a crash
 * raises 23505 → {@code DuplicateKeyException}, which the relay swallows per-handler (at-least-once delivery,
 * exactly-once effect). Because this method is its own transaction, that constraint violation rolls back
 * only this insert and never poisons a shared transaction.
 */
@Component
public class AuditProjector implements OutboxEventHandler {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditProjector(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public void handle(OutboxRecord event) {
        // Coerce every detail value to its string form BEFORE hashing and storing. The chain's
        // verifiability rests on the stored jsonb round-tripping byte-for-byte (jsonb normalizes numbers
        // and drops duplicate keys); strings survive that, numbers may not. Enforcing it here makes
        // verify independent of emit-site discipline — a future numeric field can't silently break it
        // (architect hardening note #1).
        Map<String, Object> detail = stringify(parse(event.payload()));
        String actor = firstNonBlank(str(detail.get("actor")), str(detail.get("actorId")), "system");
        String effectiveRole = str(detail.get("effectiveRole")); // nullable

        // Tail read under the relay's single-leader lock — no concurrent appender.
        String prevHash = jdbc.query("SELECT hash FROM audit.audit_events ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : AuditHasher.GENESIS);

        var preimage = AuditHasher.preimage(event.id().toString(), actor, effectiveRole, event.eventType(),
                event.aggregateType(), event.aggregateId(), event.occurredAt().toString(), detail);
        String hash = AuditHasher.hash(prevHash, preimage);

        jdbc.update("INSERT INTO audit.audit_events "
                + "(id, source_event_id, actor, effective_role, action, resource_type, resource_id, at, "
                + "detail, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                UUID.randomUUID(), event.id(), actor, effectiveRole, event.eventType(),
                event.aggregateType(), event.aggregateId(), event.occurredAt().atOffset(ZoneOffset.UTC),
                writeDetail(detail), prevHash, hash);
    }

    /** Recursively coerce scalar values to strings (keys + structure preserved; null stays null). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> stringify(Map<String, Object> in) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), stringifyValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object stringifyValue(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            return stringify((Map<String, Object>) m);
        }
        if (v instanceof java.util.List<?> list) {
            return list.stream().map(this::stringifyValue).toList();
        }
        return String.valueOf(v);
    }

    private Map<String, Object> parse(String payloadJson) {
        try {
            return json.readValue(payloadJson, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable outbox payload", e);
        }
    }

    private String writeDetail(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize audit detail", e);
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "system";
    }
}
