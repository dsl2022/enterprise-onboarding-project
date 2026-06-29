package com.eop.platform;

import java.time.Instant;
import java.util.UUID;

/**
 * One claimed {@code messaging.outbox} row, handed to {@link OutboxEventHandler}s by the relay. Carries the
 * raw payload JSON (handlers parse what they need) plus {@code occurredAt} — when the event happened, which
 * is what the audit chain records as {@code at} (NOT the relay's processing time). {@code attempts} lets a
 * handler/relay reason about retries.
 */
public record OutboxRecord(
        UUID id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payload,
        Instant occurredAt,
        int attempts) {}
