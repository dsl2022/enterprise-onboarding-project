package com.eop.notify;

import java.time.Instant;

/**
 * One in-app notification as the frozen contract exposes it. {@code createdAt} is the originating event's
 * occurred_at (causal order), not the row insert time. {@code resourceRef} (nullable) points at the
 * request/team the notification is about.
 */
public record Notification(
        String id,
        String type,
        String title,
        String body,
        String resourceRef,
        boolean read,
        Instant createdAt) {}
