package com.eop.request;

import com.eop.authz.Ownable;
import com.eop.authz.SodSubject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * The shared request aggregate. Implements {@link SodSubject} and {@link Ownable} so the engine enforces
 * separation of duties and ABAC ownership against the <b>real</b> principal without parsing the payload.
 * {@code version} is an explicit guard column (NOT JPA {@code @Version}): mutations go only through the
 * repository's guarded UPDATE, which needs "status must still be :from" — something {@code @Version}
 * can't express. Implements {@link Persistable} so a client-assigned UUID id still does an INSERT.
 */
@Entity
@Table(schema = "request", name = "requests")
public class RequestEntity implements SodSubject, Ownable, Persistable<UUID> {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private RequestType type;

    @Enumerated(EnumType.STRING)
    private RequestStatus status;

    private String requester;

    @Column(name = "submitted_by")
    private String submittedBy;

    private String approver;

    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "external_ref")
    private String externalRef;

    /** Reaper backoff counter (4b): bumped on each stale-PROVISIONING re-claim. */
    @Column(name = "provision_attempts")
    private int provisionAttempts;

    /** Reaper lease / backoff gate (4b): NULL means due now. See V5 migration. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    private int version;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Transient
    private boolean isNew = true;

    protected RequestEntity() {
    }

    public RequestEntity(UUID id, RequestType type, RequestStatus status, String requester,
            String submittedBy, String payload) {
        this.id = id;
        this.type = type;
        this.status = status;
        this.requester = requester;
        this.submittedBy = submittedBy;
        this.payload = payload == null ? "{}" : payload;
        this.version = 0;
        this.provisionAttempts = 0;
        this.nextAttemptAt = null;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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

    public RequestType getType() {
        return type;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public String getRequester() {
        return requester;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public String getApprover() {
        return approver;
    }

    public String getReason() {
        return reason;
    }

    public String getPayload() {
        return payload;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public int getProvisionAttempts() {
        return provisionAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    // --- authz: ABAC ownership + separation of duties on the REAL principal ---

    @Override
    public String ownerId() {
        return requester;
    }

    @Override
    public String requesterId() {
        return requester;
    }

    @Override
    public String submittedById() {
        return submittedBy;
    }
}
