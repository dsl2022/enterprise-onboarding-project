package com.eop.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Minimizes and bounds the untrusted wizard {@code context} before it reaches the model. Only a fixed set of
 * known wizard fields survive; unknown keys are dropped, values are size-capped, and only scalars / short
 * scalar-arrays pass (no nested objects). This is guardrail §"minimize untrusted data in context": the model
 * never sees arbitrary caller-supplied structure, which shrinks the prompt-injection surface to the user's own
 * declared field values (which are data, not instructions, and are framed as such in the system prompt).
 */
@Component
public class AssistantContextWhitelist {

    /** The only wizard fields forwarded to the model (onboarding + access wizards). */
    static final Set<String> ALLOWED_KEYS = Set.of(
            "appName", "environment", "description", "redirectUris", "requestType",
            "resourceId", "resourceName", "justification", "duration", "risk");

    static final int MAX_STRING = 2_000;
    static final int MAX_ARRAY = 20;

    /** @return a new map with only allowed, bounded, scalar(-array) entries; never null. */
    public Map<String, Object> filter(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (String key : ALLOWED_KEYS) {
            Object v = raw.get(key);
            Object clean = clean(v);
            if (clean != null) {
                out.put(key, clean);
            }
        }
        return out;
    }

    /** Keep scalars (bounded strings, numbers, booleans) and short arrays of scalars; drop everything else. */
    private Object clean(Object v) {
        if (v instanceof String s) {
            return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) : s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (Object el : list) {
                if (items.size() >= MAX_ARRAY) {
                    break;
                }
                if (el instanceof String s) {
                    items.add(s.length() > MAX_STRING ? s.substring(0, MAX_STRING) : s);
                } else if (el instanceof Number || el instanceof Boolean) {
                    items.add(el);
                }
            }
            return items;
        }
        return null; // objects / nulls / anything else: dropped
    }
}
