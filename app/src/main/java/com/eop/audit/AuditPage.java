package com.eop.audit;

import java.util.List;

/** Cursor page of audit events (contract {@code AuditPage}). {@code nextCursor} null = last page. */
public record AuditPage(List<AuditEvent> items, String nextCursor) {}
