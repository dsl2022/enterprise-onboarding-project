package com.eop.access;

import java.util.List;

/** Frozen contract {@code AccessRequestPage} (cursor pagination). */
public record AccessRequestPage(List<AccessRequest> items, String nextCursor) {
}
