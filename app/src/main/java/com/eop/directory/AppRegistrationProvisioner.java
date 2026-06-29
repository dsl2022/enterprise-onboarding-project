package com.eop.directory;

import java.util.UUID;

/**
 * Provisions the Entra app registration for an onboarding request. The 4a implementation is simulated;
 * 4b adds the real Microsoft Graph implementation (create via {@code Application.ReadWrite.OwnedBy} over
 * the WIF token). Implementations must be <b>idempotent</b> — find-or-create keyed on the request id — so
 * a retry after a lost {@code markProvisioned} write never creates a duplicate app.
 */
public interface AppRegistrationProvisioner {

    /** Find-or-create the registration for {@code requestId}; returns the client (app) id. */
    String provision(UUID requestId, String displayName);
}
