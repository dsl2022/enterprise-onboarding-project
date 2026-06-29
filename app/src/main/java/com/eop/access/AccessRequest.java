package com.eop.access;

import java.time.Instant;

/** Frozen contract {@code AccessRequest} (projection over the request engine). */
public record AccessRequest(
        String id,
        String resourceId,
        String resourceName,
        String kind,
        String status,
        String requester,
        String justification,
        String duration,
        String approver,
        Instant createdAt,
        Instant updatedAt) {
}
