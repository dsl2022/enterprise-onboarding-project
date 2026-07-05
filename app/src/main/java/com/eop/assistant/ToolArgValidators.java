package com.eop.assistant;

import com.eop.assistant.model.Rung1Tools;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The second deterministic gate (after the allow-list). {@link com.eop.assistant.model.ToolProposalValidator}
 * proves a proposal names a Rung-1 tool; this proves its <b>args</b> are well-formed for that tool, and
 * <b>normalizes them in Java</b> — most importantly for {@code validateRedirectUris}, where the authoritative
 * verdict is computed here and the model's opinion is ignored. A proposal whose args don't validate is dropped
 * (returns empty), never trusted or half-applied.
 */
@Component
public class ToolArgValidators {

    private static final int MAX_TEXT = 2_000;
    private static final int MAX_SCOPES = 20;
    private static final int MAX_URIS = 10;
    private static final Pattern SAFE_SCOPE = Pattern.compile("[A-Za-z0-9 ._:/-]{1,128}");
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    /** @return normalized, safe args for the tool, or empty if the proposal's args are unusable. */
    public Optional<Map<String, Object>> validate(String tool, Map<String, Object> args) {
        if (args == null) {
            return Optional.empty();
        }
        return switch (tool) {
            case Rung1Tools.DRAFT_DESCRIPTION -> text(args, "description");
            case Rung1Tools.DRAFT_JUSTIFICATION -> text(args, "justification");
            case Rung1Tools.RECOMMEND_SCOPES -> scopes(args);
            case Rung1Tools.VALIDATE_REDIRECT_URIS -> redirectUris(args);
            default -> Optional.empty();
        };
    }

    /** A single bounded, non-blank text field. */
    private Optional<Map<String, Object>> text(Map<String, Object> args, String field) {
        if (args.get(field) instanceof String s && !s.isBlank()) {
            String trimmed = s.strip();
            String bounded = trimmed.length() > MAX_TEXT ? trimmed.substring(0, MAX_TEXT) : trimmed;
            return Optional.of(Map.of(field, bounded));
        }
        return Optional.empty();
    }

    /** A string array of safe, bounded scope tokens (deduped, capped). May normalize to an empty list. */
    private Optional<Map<String, Object>> scopes(Map<String, Object> args) {
        if (!(args.get("scopes") instanceof List<?> raw)) {
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        for (Object el : raw) {
            if (out.size() >= MAX_SCOPES) {
                break;
            }
            if (el instanceof String s && SAFE_SCOPE.matcher(s.strip()).matches() && !out.contains(s.strip())) {
                out.add(s.strip());
            }
        }
        return Optional.of(Map.of("scopes", out));
    }

    /** URIs with an authoritative, Java-computed validity verdict per URI (the model's opinion is discarded). */
    private Optional<Map<String, Object>> redirectUris(Map<String, Object> args) {
        if (!(args.get("uris") instanceof List<?> raw)) {
            return Optional.empty();
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object el : raw) {
            if (results.size() >= MAX_URIS) {
                break;
            }
            if (el instanceof String s) {
                results.add(verdict(s.strip()));
            }
        }
        return Optional.of(Map.of("results", results));
    }

    /** Redirect-URI policy: absolute https (or http only for localhost), a host, no wildcard, no fragment. */
    private Map<String, Object> verdict(String uri) {
        String reason = reject(uri);
        return Map.of("uri", uri, "valid", reason == null, "reason", reason == null ? "ok" : reason);
    }

    private String reject(String uri) {
        if (uri.isBlank()) {
            return "empty";
        }
        if (uri.contains("*")) {
            return "wildcards are not allowed";
        }
        URI parsed;
        try {
            parsed = URI.create(uri);
        } catch (IllegalArgumentException e) {
            return "not a valid URI";
        }
        if (!parsed.isAbsolute() || parsed.getScheme() == null) {
            return "must be an absolute URI with a scheme";
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            return "must have a host";
        }
        if (parsed.getFragment() != null) {
            return "must not contain a fragment";
        }
        String scheme = parsed.getScheme().toLowerCase();
        boolean localhost = LOCAL_HOSTS.contains(parsed.getHost().toLowerCase());
        if (scheme.equals("https")) {
            return null;
        }
        if (scheme.equals("http") && localhost) {
            return null;
        }
        return "must use https (http is allowed only for localhost)";
    }
}
