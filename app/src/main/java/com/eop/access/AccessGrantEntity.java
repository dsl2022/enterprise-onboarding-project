package com.eop.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * The my-access PROJECTION row — the source of truth for "currently held" (via {@link #removedAt} IS NULL),
 * NOT the request status (a removal request ends in GRANTED-meaning-"completed"). Written atomically with
 * {@code markProvisioned} by the access worker. {@code expiresAt} is recorded but NOT enforced in v1
 * (duration is informational; the expiry sweep is a future CR). Implements {@link Persistable} so the
 * client-assigned UUID still INSERTs.
 */
@Entity
@Table(schema = "access", name = "access_grant")
public class AccessGrantEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "resource_id")
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "request_id")
    private UUID requestId;

    @Column(name = "granted_at")
    private Instant grantedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Transient
    private boolean isNew = true;

    protected AccessGrantEntity() {
    }

    public AccessGrantEntity(UUID id, String resourceId, String resourceName, String userId, UUID requestId,
            Instant grantedAt, Instant expiresAt) {
        this.id = id;
        this.resourceId = resourceId;
        this.resourceName = resourceName;
        this.userId = userId;
        this.requestId = requestId;
        this.grantedAt = grantedAt;
        this.expiresAt = expiresAt;
        this.isNew = true;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getResourceName() {
        return resourceName;
    }

    public String getUserId() {
        return userId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }
}
