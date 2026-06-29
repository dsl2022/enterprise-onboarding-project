package com.eop.access;

import com.eop.request.RequestService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <b>atomic completion</b> of an access provisioning: {@code markProvisioned} (request schema,
 * PROVISIONING → GRANTED) AND the {@code access_grant} projection write (access schema) commit in ONE
 * transaction, so there is never drift (GRANTED with no /my-access row, or vice versa). A separate bean
 * from the worker so the {@code @Transactional} proxy actually applies (self-invocation wouldn't).
 *
 * <p>The Graph call happens in the worker BEFORE this — never inside the transaction. If
 * {@code markProvisioned} loses a race (already GRANTED), it throws and the whole tx (incl. the projection
 * write) rolls back.
 */
@Service
public class AccessGrantService {

    private final RequestService engine;
    private final AccessGrantRepository grants;

    public AccessGrantService(RequestService engine, AccessGrantRepository grants) {
        this.engine = engine;
        this.grants = grants;
    }

    /** Complete a GRANT: GRANTED + insert the projection row (idempotent by requestId). */
    @Transactional
    public void completeGrant(UUID requestId, String resourceId, String resourceName, String userId,
            Instant expiresAt, String externalRef) {
        engine.markProvisioned(requestId, externalRef);
        if (grants.findByRequestId(requestId).isEmpty()) {
            grants.save(new AccessGrantEntity(UUID.randomUUID(), resourceId, resourceName, userId, requestId,
                    Instant.now(), expiresAt));
        }
    }

    /** Complete a REMOVAL: GRANTED ("completed") + mark the active grant removed (idempotent). */
    @Transactional
    public void completeRemoval(UUID requestId, String resourceId, String userId, String externalRef) {
        engine.markProvisioned(requestId, externalRef);
        grants.markRemoved(userId, resourceId, Instant.now());
    }
}
