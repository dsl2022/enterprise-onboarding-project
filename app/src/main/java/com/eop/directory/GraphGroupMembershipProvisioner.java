package com.eop.directory;

import com.eop.wif.WifAssertionService;
import java.util.Locale;
import java.util.Map;
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
 * The real Phase-5b group-membership provisioner: adds/removes an Entra group member via Microsoft Graph
 * using the app-only token from {@link WifAssertionService} ({@code GroupMember.ReadWrite.All}) — no new
 * credential. Active when {@code eop.provisioning.access.simulate=false} (which also requires
 * {@code wif.enabled=true}); otherwise {@link SimulatedGroupProvisioner} stays in charge. The member id is
 * the user's Entra <b>object id</b> (the {@code oid} the principal already carries) — NOT the app-pairwise
 * {@code sub}, which is not a directory object.
 *
 * <p><b>Idempotent by specific Graph signal</b>, so the worker's retry/reaper is safe (and it composes with
 * the DB-side {@code completeGrant} idempotency):
 * <ul>
 *   <li><b>add → already a member:</b> Graph 400 {@code Request_BadRequest} whose message contains
 *       "already exist" → treated as success (tolerant, case-insensitive match — Graph gives no distinct
 *       code). Any other 400, or a 404 (bad group / object), is rethrown so a real error surfaces.</li>
 *   <li><b>remove → not a member:</b> Graph 404 {@code Request_ResourceNotFound} → treated as success
 *       (the desired state — not a member — is already achieved).</li>
 *   <li><b>403</b> is surfaced as its own loud "missing GroupMember.ReadWrite.All consent" error, never
 *       folded into the 400 handling.</li>
 *   <li><b>429</b> backs off on {@code Retry-After}; transient 5xx/IO retries.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "eop.provisioning.access.simulate", havingValue = "false")
public class GraphGroupMembershipProvisioner implements GroupMembershipProvisioner {

    private static final Logger log = LoggerFactory.getLogger(GraphGroupMembershipProvisioner.class);
    private static final int MAX_ATTEMPTS = 4;

    private final WifAssertionService wif;
    private final RestClient http;
    private final String baseUrl;

    public GraphGroupMembershipProvisioner(WifAssertionService wif, RestClient.Builder builder,
            @Value("${eop.graph.base-url:https://graph.microsoft.com/v1.0}") String baseUrl) {
        this.wif = wif;
        this.baseUrl = baseUrl;
        this.http = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String addMember(String groupId, String userId) {
        String token = wif.graphToken();
        // Graph add takes a directoryObjects $ref; the member MUST be the oid (a real directory object).
        Map<String, Object> body = Map.of("@odata.id", baseUrl + "/directoryObjects/" + userId);

        for (int attempt = 0; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                http.post()
                        .uri("/groups/{groupId}/members/$ref", groupId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Added member {} to group {}", userId, groupId);
                return ref(groupId, userId);
            } catch (HttpClientErrorException.TooManyRequests e) {
                backoff(retryAfterSeconds(e, attempt), attempt);
            } catch (HttpClientErrorException.Forbidden e) {
                throw consentError(e);
            } catch (HttpClientErrorException.BadRequest e) {
                if (alreadyMember(e)) {
                    log.info("Member {} already in group {} — treating add as success (idempotent)", userId, groupId);
                    return ref(groupId, userId);
                }
                throw e; // a genuine bad request (e.g. malformed) — surface it
            } catch (HttpServerErrorException | ResourceAccessException e) {
                log.warn("Graph add-member transient failure (attempt {}/{}): {}", attempt + 1, MAX_ATTEMPTS + 1, e.toString());
                backoff((long) Math.pow(2, attempt), attempt);
            }
            // NotFound (bad group/object) and any other 4xx are NOT caught here → they propagate (surface).
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Graph add-member failed after " + (MAX_ATTEMPTS + 1) + " attempts");
    }

    @Override
    public void removeMember(String groupId, String userId) {
        String token = wif.graphToken();
        for (int attempt = 0; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                http.delete()
                        .uri("/groups/{groupId}/members/{userId}/$ref", groupId, userId)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .toBodilessEntity();
                log.info("Removed member {} from group {}", userId, groupId);
                return;
            } catch (HttpClientErrorException.TooManyRequests e) {
                backoff(retryAfterSeconds(e, attempt), attempt);
            } catch (HttpClientErrorException.Forbidden e) {
                throw consentError(e);
            } catch (HttpClientErrorException.NotFound e) {
                log.info("Member {} not in group {} — treating removal as success (idempotent)", userId, groupId);
                return; // desired state (not a member) already achieved
            } catch (HttpServerErrorException | ResourceAccessException e) {
                log.warn("Graph remove-member transient failure (attempt {}/{}): {}", attempt + 1, MAX_ATTEMPTS + 1, e.toString());
                backoff((long) Math.pow(2, attempt), attempt);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Graph remove-member failed after " + (MAX_ATTEMPTS + 1) + " attempts");
    }

    /** Deterministic grant marker — Graph add returns 204 (no id), so this arms the worker's DB-first skip. */
    private String ref(String groupId, String userId) {
        return groupId + ":" + userId;
    }

    /** Tolerant "already a member" detection — Graph gives no distinct code, only a 400 with this message. */
    private boolean alreadyMember(HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        return body != null && body.toLowerCase(Locale.ROOT).contains("already exist");
    }

    private ResponseStatusException consentError(HttpClientErrorException e) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Graph 403 — the app likely lacks admin-consented GroupMember.ReadWrite.All", e);
    }

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
                    "Graph group-membership call failed after " + (MAX_ATTEMPTS + 1) + " attempts");
        }
        try {
            Thread.sleep(Math.max(0, seconds) * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted during Graph backoff", ie);
        }
    }
}
