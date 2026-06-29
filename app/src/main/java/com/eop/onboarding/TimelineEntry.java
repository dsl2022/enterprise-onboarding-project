package com.eop.onboarding;

import java.time.Instant;

/** Frozen contract {@code TimelineEntry}, projected from a request_events row. */
public record TimelineEntry(String id, String status, String actor, String reason, Instant at) {
}
