package com.eop.graph;

import com.eop.wif.WifAssertionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Flow 2c — calls real Microsoft Graph as the app, using the token from {@link WifAssertionService}.
 * Handles the behaviors Graph really exhibits: {@code @odata.nextLink} pagination, {@code 429} with
 * {@code Retry-After} (exponential backoff), and a clear {@code 403} when admin consent is missing.
 */
@Service
@ConditionalOnProperty(prefix = "wif", name = "enabled", havingValue = "true")
public class GraphService {

    private static final Logger log = LoggerFactory.getLogger(GraphService.class);
    private static final String START_URL =
            "https://graph.microsoft.com/v1.0/groups?$select=id,displayName&$top=20";
    private static final int MAX_PAGES = 10;
    private static final int MAX_RETRIES = 4;

    private final WifAssertionService wif;
    private final RestClient http = RestClient.create();

    public GraphService(WifAssertionService wif) {
        this.wif = wif;
    }

    public List<Map<String, Object>> listGroups() {
        String token = wif.graphToken();
        var groups = new ArrayList<Map<String, Object>>();
        String url = START_URL;
        int pages = 0;
        while (url != null && pages < MAX_PAGES) {
            Map<String, Object> page = getPage(url, token);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> value = (List<Map<String, Object>>) page.getOrDefault("value", List.of());
            groups.addAll(value);
            url = (String) page.get("@odata.nextLink");
            pages++;
        }
        log.info("Graph /groups returned {} groups across {} page(s)", groups.size(), pages);
        return groups;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getPage(String url, String token) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return http.get()
                        .uri(url)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .body(Map.class);
            } catch (HttpClientErrorException.TooManyRequests e) {
                long wait = retryAfterSeconds(e, attempt);
                if (attempt == MAX_RETRIES) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "Graph throttled after " + MAX_RETRIES + " retries", e);
                }
                log.warn("Graph 429; backing off {}s (attempt {})", wait, attempt + 1);
                sleep(wait);
            } catch (HttpClientErrorException.Forbidden e) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Graph 403 — the app likely lacks admin-consented Group.Read.All", e);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Graph request failed");
    }

    /** Retry-After header (seconds) if present, else exponential backoff. */
    private long retryAfterSeconds(HttpClientErrorException e, int attempt) {
        var headers = e.getResponseHeaders();
        if (headers != null) {
            String ra = headers.getFirst("Retry-After");
            if (ra != null) {
                try {
                    return Long.parseLong(ra.trim());
                } catch (NumberFormatException ignored) {
                    // Retry-After can be an HTTP date; fall through to backoff for v0.
                }
            }
        }
        return (long) Math.pow(2, attempt); // 1, 2, 4, 8 ...
    }

    private void sleep(long seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted during Graph backoff", ie);
        }
    }
}
