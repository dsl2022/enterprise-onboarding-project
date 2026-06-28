package com.eop.platform;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Appends a domain event to the shared {@code messaging.outbox} in the <b>caller's transaction</b> (the
 * JdbcTemplate shares the JPA-managed connection), so an event is written iff its state change commits.
 *
 * <p>Deliberately generic — it takes only primitives, so {@code platform} never depends on any module's
 * event types, and every emitter (request/onboarding/access/audit) plus the Phase 6 relay touch this one
 * table only. Events need not correspond to a state transition: a worker may append a
 * {@code provisioning_failed} event with no status change so notify/audit can surface a stuck request.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        jdbc.update(
                "INSERT INTO messaging.outbox (id, aggregate_type, aggregate_id, event_type, payload) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb)",
                UUID.randomUUID(), aggregateType, aggregateId, eventType, payloadJson);
    }
}
