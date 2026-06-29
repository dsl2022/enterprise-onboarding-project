package com.eop.onboarding;

import java.util.List;

/** Frozen contract {@code ApplicationCreate}. {@code name}/{@code env} are required and immutable after. */
public record ApplicationCreate(
        String name,
        String env,
        String description,
        List<String> grants,
        List<String> scopes,
        List<String> uris,
        String group,
        List<String> team) {
}
