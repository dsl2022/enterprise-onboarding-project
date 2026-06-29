package com.eop.access;

import java.time.Instant;

/** Frozen contract {@code MyAccessItem} (a currently-held grant). */
public record MyAccessItem(String resourceId, String resourceName, Instant grantedAt, String requestId) {
}
