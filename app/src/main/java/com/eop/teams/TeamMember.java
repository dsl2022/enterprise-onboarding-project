package com.eop.teams;

import java.time.Instant;

/** Frozen contract {@code TeamMember}. {@code name} (display name) is not resolved in v1 portal-local
 *  teams (would need a directory lookup) — returned as null; the frontend resolves names separately. */
public record TeamMember(String userId, String name, Instant addedAt) {
}
