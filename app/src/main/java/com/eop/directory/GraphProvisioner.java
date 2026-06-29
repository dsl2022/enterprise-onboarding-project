package com.eop.directory;

import com.eop.wif.WifAssertionService;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * The real Phase-4b provisioner: creates an Entra <b>app registration</b> via Microsoft Graph
 * ({@code POST /applications}) using the app-only token from {@link WifAssertionService} — no new
 * credential. Active when {@code eop.provisioning.simulate=false} (and therefore requires
 * {@code wif.enabled=true}, since it injects {@link WifAssertionService}); otherwise the
 * {@link SimulatedProvisioner} stays in charge.
 *
 * <p><b>Idempotency is find-or-create keyed on the request id</b>, carried as a Graph
 * {@code tags:["eop:requestId:{uuid}"]} marker (purpose-built, {@code $filter}-queryable, no semantic
 * side effects). The <b>whole find-or-create is the atomic retry unit</b>: every retry — throttle (429),
 * ambiguous server/IO failure where a create may have committed but the response was lost — re-runs the
 * tag find FIRST, never a bare repeat POST, so a lost-response create can't yield a duplicate. Graph
 * doesn't enforce tag uniqueness, so a {@code $filter} match of >1 is resolved deterministically (earliest
 * {@code createdDateTime}, tie-break {@code appId}) and loudly logged so a slipped-through duplicate stays
 * detectable.
 *
 * <p>The {@code tags/any(...)} {@code $filter} on {@code /applications} is an <b>advanced query</b>: it
 * requires the {@code ConsistencyLevel: eventual} header + {@code $count=true}. The find is eventually
 * consistent, but the worker's stale-PROVISIONING reaper backoff outlasts that lag, so a re-attempt sees
 * the prior create.
 *
 * <p><b>Scope limit:</b> {@code Application.ReadWrite.OwnedBy} creates a registration + client id, NOT a
 * service principal — the onboarded app isn't sign-in-capable until an SP exists (a future CR; an SP needs
 * the broader {@code Application.ReadWrite.All}, withheld here for least privilege).
 */
@Component
@ConditionalOnProperty(name = "eop.provisioning.simulate", havingValue = "false")
public class GraphProvisioner implements AppRegistrationProvisioner {

    private static final Logger log = LoggerFactory.getLogger(GraphProvisioner.class);
    private static final int MAX_ATTEMPTS = 4;

    private final WifAssertionService wif;
    private final RestClient http;

    public GraphProvisioner(WifAssertionService wif, RestClient.Builder builder,
            @Value("${eop.graph.base-url:https://graph.microsoft.com/v1.0}") String baseUrl) {
        this.wif = wif;
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String provision(UUID requestId, String displayName) {
        String tag = "eop:requestId:" + requestId;
        String token = wif.graphToken();

        for (int attempt = 0; attempt <= MAX_ATTEMPTS; attempt++) {
            // Atomic retry unit: always (re)find before attempting a create. A retry after a lost-response
            // create therefore reuses the already-created app instead of making a second one.
            String existing = findByTag(tag, token);
            if (existing != null) {
                return existing;
            }
            try {
                return create(displayName, tag, token);
            } catch (HttpClientErrorException.TooManyRequests e) {
                backoff(retryAfterSeconds(e, attempt), attempt);
            } catch (HttpClientErrorException.Forbidden e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Graph 403 — the app likely lacks admin-consented Application.ReadWrite.OwnedBy", e);
            } catch (HttpServerErrorException | ResourceAccessException e) {
                // Ambiguous: the create may have committed at Graph but the response was lost. Do NOT
                // re-POST blindly — loop back so the next iteration's findByTag picks up any committed app.
                log.warn("Graph create ambiguous failure (attempt {}/{}), will re-find then retry: {}",
                        attempt + 1, MAX_ATTEMPTS + 1, e.toString());
                backoff((long) Math.pow(2, attempt), attempt);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Graph app registration failed after " + (MAX_ATTEMPTS + 1) + " attempts");
    }

    /** Tag find. Advanced query: needs {@code ConsistencyLevel: eventual} + {@code $count=true}. */
    @SuppressWarnings("unchecked")
    private String findByTag(String tag, String token) {
        Map<String, Object> resp = http.get()
                .uri(b -> b.path("/applications")
                        .queryParam("$filter", "tags/any(t:t eq '" + tag + "')")
                        .queryParam("$count", "true")
                        .queryParam("$select", "appId,id,displayName,createdDateTime")
                        .build())
                .header("Authorization", "Bearer " + token)
                .header("ConsistencyLevel", "eventual")
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> value =
                (List<Map<String, Object>>) (resp == null ? List.of() : resp.getOrDefault("value", List.of()));
        if (value.isEmpty()) {
            return null;
        }
        if (value.size() > 1) {
            log.warn("DUPLICATE Entra app registrations for tag {} — {} found; picking deterministically. "
                    + "Investigate: tags are not uniqueness-enforced by Graph.", tag, value.size());
        }
        // Deterministic pick: earliest createdDateTime, tie-break appId. Both compared as strings (ISO-8601
        // createdDateTime sorts lexicographically; nulls last so a malformed row never wins silently).
        Map<String, Object> chosen = value.stream()
                .min(Comparator
                        .comparing((Map<String, Object> m) -> str(m, "createdDateTime"),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(m -> str(m, "appId"),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .orElseThrow();
        return (String) chosen.get("appId");
    }

    @SuppressWarnings("unchecked")
    private String create(String displayName, String tag, String token) {
        Map<String, Object> body = Map.of(
                "displayName", displayName,
                "signInAudience", "AzureADMyOrg",   // single-tenant: matches the portal
                "tags", List.of(tag));
        Map<String, Object> resp = http.post()
                .uri("/applications")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        String appId = (String) (resp == null ? null : resp.get("appId"));
        log.info("Created Entra app registration '{}' (tag {}) -> clientId {}", displayName, tag, appId);
        return appId;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    /** Retry-After seconds if present, else exponential backoff (mirrors GraphService). */
    private long retryAfterSeconds(HttpClientErrorException e, int attempt) {
        var headers = e.getResponseHeaders();
        if (headers != null) {
            String ra = headers.getFirst("Retry-After");
            if (ra != null) {
                try {
                    return Long.parseLong(ra.trim());
                } catch (NumberFormatException ignored) {
                    // Retry-After may be an HTTP date; fall through to backoff.
                }
            }
        }
        return (long) Math.pow(2, attempt);
    }

    private void backoff(long seconds, int attempt) {
        if (attempt == MAX_ATTEMPTS) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Graph app registration failed after " + (MAX_ATTEMPTS + 1) + " attempts");
        }
        try {
            Thread.sleep(Math.max(0, seconds) * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted during Graph backoff", ie);
        }
    }
}
