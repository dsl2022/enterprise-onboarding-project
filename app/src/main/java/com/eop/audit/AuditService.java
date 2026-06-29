package com.eop.audit;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.platform.CursorCodec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads + verifies the audit log (write side is {@link AuditProjector}). {@code audit.read} gates both
 * endpoints (SSO_OPS / ADMIN / AUDITOR / SUPER_ADMIN). Verify recomputes the chain from genesis ordered by
 * seq, tolerating seq gaps — integrity is {@code prev_hash} linkage, not contiguity — and rebuilds each
 * row's pre-image with the SAME {@link AuditHasher} the projector used, so a faithful chain re-hashes
 * identically.
 */
@Service
public class AuditService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 20;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuthorizationService authz;

    public AuditService(JdbcTemplate jdbc, ObjectMapper json, AuthorizationService authz) {
        this.jdbc = jdbc;
        this.json = json;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public AuditPage query(CurrentPrincipal principal, String actor, String type, String resource,
            Instant from, Instant to, String cursor, int limit) {
        authz.require(principal, Permission.AUDIT_READ); // 403 — audit.read, unscoped
        int page = CursorCodec.toPage(cursor);
        int lim = Math.min(limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);

        StringBuilder sql = new StringBuilder(
                "SELECT id, seq, actor, effective_role, action, resource_type, resource_id, at, "
                        + "prev_hash, hash, detail FROM audit.audit_events WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (hasText(actor)) {
            sql.append(" AND actor = ?");
            args.add(actor.trim());
        }
        if (hasText(type)) {
            sql.append(" AND resource_type = ?");
            args.add(type.trim());
        }
        if (hasText(resource)) {
            sql.append(" AND resource_id = ?");
            args.add(resource.trim());
        }
        if (from != null) {
            sql.append(" AND at >= ?");
            args.add(from.atOffset(ZoneOffset.UTC));
        }
        if (to != null) {
            sql.append(" AND at <= ?");
            args.add(to.atOffset(ZoneOffset.UTC));
        }
        sql.append(" ORDER BY seq DESC OFFSET ? LIMIT ?");
        args.add((long) page * lim);
        args.add(lim + 1); // fetch one extra to detect a next page

        List<AuditEvent> rows = jdbc.query(sql.toString(), AUDIT_EVENT_MAPPER, args.toArray());
        boolean hasNext = rows.size() > lim;
        List<AuditEvent> items = hasNext ? rows.subList(0, lim) : rows;
        return new AuditPage(List.copyOf(items), CursorCodec.nextCursor(page, hasNext));
    }

    @Transactional(readOnly = true)
    public AuditVerifyResult verify(CurrentPrincipal principal) {
        authz.require(principal, Permission.AUDIT_READ);
        List<ChainRow> chain = jdbc.query(
                "SELECT seq, source_event_id, actor, effective_role, action, resource_type, resource_id, "
                        + "at, detail, prev_hash, hash FROM audit.audit_events ORDER BY seq",
                CHAIN_ROW_MAPPER);
        String expectedPrev = AuditHasher.GENESIS;
        long checkedThrough = 0;
        for (ChainRow r : chain) {
            // 1. linkage — this row's prev_hash must equal the previous row's hash
            if (!expectedPrev.equals(r.prevHash())) {
                return new AuditVerifyResult(false, checkedThrough, r.seq());
            }
            // 2. content — recompute from the row's own fields, rebuilt identically to the insert
            var preimage = AuditHasher.preimage(r.sourceEventId(), r.actor(), r.effectiveRole(), r.action(),
                    r.resourceType(), r.resourceId(), r.at().toString(), r.detail());
            if (!AuditHasher.hash(r.prevHash(), preimage).equals(r.hash())) {
                return new AuditVerifyResult(false, checkedThrough, r.seq());
            }
            checkedThrough = r.seq();
            expectedPrev = r.hash();
        }
        return new AuditVerifyResult(true, checkedThrough, null);
    }

    /** Internal verify row — carries source_event_id (not in the public DTO) and the raw detail map. */
    private record ChainRow(long seq, String sourceEventId, String actor, String effectiveRole, String action,
            String resourceType, String resourceId, Instant at, Map<String, Object> detail, String prevHash,
            String hash) {}

    private final RowMapper<AuditEvent> AUDIT_EVENT_MAPPER = (rs, n) -> new AuditEvent(
            rs.getString("id"), rs.getLong("seq"), rs.getString("actor"), rs.getString("effective_role"),
            rs.getString("action"), rs.getString("resource_type"), rs.getString("resource_id"),
            rs.getObject("at", OffsetDateTime.class).toInstant(), rs.getString("prev_hash"),
            rs.getString("hash"), parse(rs.getString("detail")));

    private final RowMapper<ChainRow> CHAIN_ROW_MAPPER = (rs, n) -> new ChainRow(
            rs.getLong("seq"), rs.getString("source_event_id"), rs.getString("actor"),
            rs.getString("effective_role"), rs.getString("action"), rs.getString("resource_type"),
            rs.getString("resource_id"), rs.getObject("at", OffsetDateTime.class).toInstant(),
            parse(rs.getString("detail")), rs.getString("prev_hash"), rs.getString("hash"));

    private Map<String, Object> parse(String jsonText) {
        try {
            return new LinkedHashMap<>(json.readValue(jsonText, MAP));
        } catch (Exception ex) {
            throw new IllegalStateException("unparseable audit detail", ex);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
