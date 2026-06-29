package com.eop.platform;

/**
 * SPI implemented by projection modules (audit in 6a; notify in 6b) so the platform-owned {@link OutboxRelay}
 * can dispatch each outbox event without depending on any module (dependency inversion — the relay depends on
 * this interface, modules depend on platform). The relay calls every registered handler for each event, in
 * order, while holding the single-leader lock.
 *
 * <p><b>Idempotency contract:</b> a handler MUST be safe to call more than once for the same {@link
 * OutboxRecord} (at-least-once delivery — the relay can crash after a handler commits but before the outbox
 * row is marked published). Signal "already projected this event" by throwing a Spring
 * {@code DuplicateKeyException} (e.g. from a UNIQUE on the source event id); the relay swallows it per-handler
 * and proceeds. Any OTHER exception means "transient failure" — the relay defers the row (backoff) and stops,
 * preserving ordering, so a poison event cannot be skipped.
 */
public interface OutboxEventHandler {

    void handle(OutboxRecord event);
}
