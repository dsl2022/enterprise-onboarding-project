package com.eop.onboarding;

import java.time.Instant;
import java.util.List;

/** Frozen contract {@code Application}. {@code clientId} is null until provisioning records it. */
public record Application(
        String id,
        String name,
        String env,
        String description,
        String status,
        String owner,
        List<String> team,
        List<String> grants,
        List<String> scopes,
        List<String> redirectUris,
        String group,
        String clientId,
        Instant createdAt,
        Instant updatedAt) {
}
