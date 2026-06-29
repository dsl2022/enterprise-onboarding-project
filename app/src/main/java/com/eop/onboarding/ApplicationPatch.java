package com.eop.onboarding;

import java.util.List;

/** Frozen contract {@code ApplicationPatch} — a partial edit (no {@code name}/{@code env}); merge semantics. */
public record ApplicationPatch(
        String description,
        List<String> grants,
        List<String> scopes,
        List<String> uris,
        String group,
        List<String> team) {
}
