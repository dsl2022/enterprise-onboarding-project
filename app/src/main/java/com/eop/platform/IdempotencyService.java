package com.eop.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Exactly-once HTTP semantics for POSTs carrying an {@code Idempotency-Key}: the DB guard already gives
 * exactly-once side effects; this gives replay-returns-the-original-response.
 *
 * <p><b>Claim-first</b> (not check-then-act): the row is INSERTed PENDING before the action runs, so two
 * concurrent identical requests can't both execute — the PK {@code (principal, endpoint, key)} is the
 * lock. The winner runs the action; on success it stores the response (replay returns it); on failure it
 * releases the claim (a transient error stays retryable). A loser sees: same key + different body → 422;
 * still PENDING → 409 (in progress); COMPLETE → the stored response. Keys live 24h (stale rows are
 * reclaimed at claim time).
 */
@Service
public class IdempotencyService {

    /** What the wrapped action produced — serialized and stored for replay. */
    public record Outcome(int status, String etag, Object body) {
    }

    private static final long TTL_HOURS = 24;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public IdempotencyService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public ResponseEntity<String> execute(String principal, String endpoint, String key, String requestBody,
            Supplier<Outcome> action) {
        String hash = sha256(requestBody == null ? "" : requestBody);

        if (claim(principal, endpoint, key, hash)) {
            try {
                Outcome outcome = action.get();
                String bodyJson = serialize(outcome.body());
                complete(principal, endpoint, key, outcome.status(), outcome.etag(), bodyJson);
                return build(outcome.status(), outcome.etag(), bodyJson);
            } catch (RuntimeException ex) {
                release(principal, endpoint, key); // failure → key is free to retry
                throw ex;
            }
        }
        return replayOrConflict(principal, endpoint, key, hash);
    }

    private boolean claim(String principal, String endpoint, String key, String hash) {
        // Reclaim a stale (>24h) key so it becomes reusable, then attempt the atomic INSERT-claim.
        jdbc.update("DELETE FROM platform.idempotency_keys WHERE principal=? AND endpoint=? AND idempotency_key=? AND created_at < ?",
                principal, endpoint, key, java.sql.Timestamp.from(Instant.now().minus(TTL_HOURS, ChronoUnit.HOURS)));
        try {
            jdbc.update("INSERT INTO platform.idempotency_keys "
                    + "(principal, endpoint, idempotency_key, request_hash, status) VALUES (?, ?, ?, ?, 'PENDING')",
                    principal, endpoint, key, hash);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private ResponseEntity<String> replayOrConflict(String principal, String endpoint, String key, String hash) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT request_hash, status, response_status, response_etag, response_body "
                        + "FROM platform.idempotency_keys WHERE principal=? AND endpoint=? AND idempotency_key=?",
                principal, endpoint, key);
        if (rows.isEmpty()) {
            // The holder released between our INSERT-conflict and this read; treat as in-progress.
            throw new ConflictException("request with this Idempotency-Key is in progress");
        }
        Map<String, Object> row = rows.get(0);
        if (!hash.equals(row.get("request_hash"))) {
            throw new UnprocessableException("Idempotency-Key reused with a different request body");
        }
        if (!"COMPLETE".equals(row.get("status"))) {
            throw new ConflictException("request with this Idempotency-Key is in progress");
        }
        return build((Integer) row.get("response_status"), (String) row.get("response_etag"),
                (String) row.get("response_body"));
    }

    private void complete(String principal, String endpoint, String key, int status, String etag, String body) {
        jdbc.update("UPDATE platform.idempotency_keys SET status='COMPLETE', response_status=?, "
                + "response_etag=?, response_body=? WHERE principal=? AND endpoint=? AND idempotency_key=?",
                status, etag, body, principal, endpoint, key);
    }

    private void release(String principal, String endpoint, String key) {
        jdbc.update("DELETE FROM platform.idempotency_keys WHERE principal=? AND endpoint=? AND idempotency_key=?",
                principal, endpoint, key);
    }

    private ResponseEntity<String> build(int status, String etag, String body) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (etag != null) {
            b.header(org.springframework.http.HttpHeaders.ETAG, etag); // etag is the already-quoted value
        }
        return b.body(body);
    }

    private String serialize(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize idempotent response", e);
        }
    }

    private String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
