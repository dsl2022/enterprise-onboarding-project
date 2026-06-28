package com.eop.request;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One append-only timeline entry. A global {@code bigserial} id gives total order so non-transition
 * entries (e.g. {@code PROVISIONING_FAILED}) can be appended safely off the version-serialized path.
 * For a transition, {@code fromStatus}/{@code toStatus} are set; for a pure event they may be null.
 */
@Entity
@Table(schema = "request", name = "request_events")
public class RequestEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id")
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private RequestStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private RequestStatus toStatus;

    @Column(name = "event_type")
    private String eventType;

    private String actor;

    @Column(name = "effective_role")
    private String effectiveRole;

    private String reason;

    private Instant at;

    protected RequestEventEntity() {
    }

    public RequestEventEntity(UUID requestId, RequestStatus fromStatus, RequestStatus toStatus,
            String eventType, String actor, String effectiveRole, String reason, Instant at) {
        this.requestId = requestId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.eventType = eventType;
        this.actor = actor;
        this.effectiveRole = effectiveRole;
        this.reason = reason;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public RequestStatus getFromStatus() {
        return fromStatus;
    }

    public RequestStatus getToStatus() {
        return toStatus;
    }

    public String getEventType() {
        return eventType;
    }

    public String getActor() {
        return actor;
    }

    public String getEffectiveRole() {
        return effectiveRole;
    }

    public String getReason() {
        return reason;
    }

    public Instant getAt() {
        return at;
    }
}
