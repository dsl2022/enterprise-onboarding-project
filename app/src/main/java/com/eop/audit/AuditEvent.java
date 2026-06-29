package com.eop.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One audit-log row as the frozen contract exposes it. {@code seq} is the contract's int64 ordinal (monotonic,
 * may gap); {@code effectiveRole} is nullable (system + impersonation); {@code detail} is the original event
 * payload. {@code prevHash}/{@code hash} are surfaced so a client can independently re-verify the chain.
 */
public record AuditEvent(
        String id,
        long seq,
        String actor,
        String effectiveRole,
        String action,
        String resourceType,
        String resourceId,
        Instant at,
        String prevHash,
        String hash,
        Map<String, Object> detail) {}
