package com.eop.request;

import com.eop.authz.AuthorizationService;
import com.eop.authz.CurrentPrincipal;
import com.eop.authz.Permission;
import com.eop.platform.ConflictException;
import com.eop.platform.NotFoundException;
import com.eop.platform.OutboxWriter;
import com.eop.platform.PreconditionFailedException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared request workflow engine that Phases 4 (onboarding) and 5 (access) drive. One aggregate, one
 * state machine, one guarded serializer; every transition records a timeline entry and an outbox event in
 * the same transaction. Authorization is enforced here at the service layer — and crucially BEFORE any
 * state is revealed, so an unauthorized caller never learns whether a request is stale or decidable.
 *
 * <p>Check order on every mutation: load (404) → authorize on the real principal (403) → If-Match (412)
 * → legal-from (409) → guarded UPDATE (409/412 on a lost race) → same-tx event + outbox.
 */
@Service
public class RequestService {

    private static final String AGGREGATE = "request";
    private static final String SYSTEM = "system";

    private final RequestRepository requests;
    private final RequestEventRepository events;
    private final OutboxWriter outbox;
    private final AuthorizationService authz;
    private final ObjectMapper json;

    public RequestService(RequestRepository requests, RequestEventRepository events, OutboxWriter outbox,
            AuthorizationService authz, ObjectMapper json) {
        this.requests = requests;
        this.events = events;
        this.outbox = outbox;
        this.authz = authz;
        this.json = json;
    }

    /**
     * Create a request in its initial state. Type-specific create authorization + payload validation are
     * the calling (Phase 4/5) service's job; this is the engine primitive. An access request, having no
     * separate submit, auto-advances into review.
     */
    @Transactional
    public RequestEntity create(RequestType type, String requesterId, String submittedById, String payloadJson) {
        RequestStatus initial = RequestTransitions.initialStatus(type);
        RequestEntity entity = new RequestEntity(UUID.randomUUID(), type, initial, requesterId, submittedById, payloadJson);
        requests.save(entity);
        recordEvent(entity.getId(), null, initial, "CREATED", submittedById, null, null);
        emit(entity, "request.created");
        if (type == RequestType.ACCESS) {
            return advance(entity, RequestStatus.UNDER_REVIEW, null, null, null, submittedById, null,
                    "AUTO_UNDER_REVIEW", "request.under_review");
        }
        return entity;
    }

    /** Owner submits (or resubmits after changes): DRAFT/CHANGES_REQUESTED → SUBMITTED → UNDER_REVIEW. */
    @Transactional
    public RequestEntity submit(CurrentPrincipal principal, UUID id, Integer ifMatchVersion) {
        RequestEntity entity = load(id);
        Permission permission = entity.getType() == RequestType.ONBOARDING
                ? Permission.APP_SUBMIT : Permission.ACCESS_REQUEST;
        authz.require(principal, permission, entity);          // 403 — permission + ABAC ownership
        checkIfMatch(ifMatchVersion, entity);                  // 412
        if (!RequestTransitions.canSubmitFrom(entity.getType(), entity.getStatus())) {
            throw new ConflictException("cannot submit from " + entity.getStatus()); // 409
        }
        String role = authz.displayRole(principal.effectiveRoles()).name();
        RequestEntity submitted = advance(entity, RequestStatus.SUBMITTED, null, null, null,
                principal.realUserId(), role, "SUBMITTED", "request.submitted");
        return advance(submitted, RequestStatus.UNDER_REVIEW, null, null, null,
                principal.realUserId(), role, "AUTO_UNDER_REVIEW", "request.under_review");
    }

    /** Approver decision from UNDER_REVIEW. Permission + SoD on the real principal; identity is real. */
    @Transactional
    public RequestEntity decide(CurrentPrincipal principal, UUID id, Decision decision, String reason,
            Integer ifMatchVersion) {
        RequestEntity entity = load(id);
        Permission permission = entity.getType() == RequestType.ONBOARDING
                ? Permission.APP_DECIDE : Permission.ACCESS_DECIDE;
        authz.requireDecision(principal, permission, entity); // 403 — permission + SoD (real principal)
        checkIfMatch(ifMatchVersion, entity);                 // 412
        RequestStatus to = RequestTransitions.afterDecision(entity.getStatus(), decision); // 409 if not UNDER_REVIEW
        String role = authz.displayRole(principal.effectiveRoles()).name();
        return advance(entity, to, principal.realUserId(), reason, null,
                principal.realUserId(), role, "DECISION_" + decision, "request." + to.name().toLowerCase());
    }

    // ---- provisioning: called by the Phase 4/5 worker (system actor) ----

    /**
     * Claim a request for provisioning: APPROVED → PROVISIONING via the guarded UPDATE. With ≥2 tasks,
     * exactly one poller wins (rowcount=1) and is the only one that calls Graph — the serializer is the
     * work-lock, preventing double-provisioning.
     */
    @Transactional
    public RequestEntity markProvisioning(UUID id) {
        RequestEntity entity = load(id);
        if (entity.getStatus() != RequestStatus.APPROVED) {
            throw new ConflictException("not APPROVED: " + entity.getStatus());
        }
        return advance(entity, RequestStatus.PROVISIONING, null, null, null, SYSTEM, null,
                "PROVISIONING", "request.provisioning");
    }

    /** Complete provisioning: PROVISIONING → ACTIVE/GRANTED, persisting the external ref (e.g. client id). */
    @Transactional
    public RequestEntity markProvisioned(UUID id, String externalRef) {
        RequestEntity entity = load(id);
        if (entity.getStatus() != RequestStatus.PROVISIONING) {
            throw new ConflictException("not PROVISIONING: " + entity.getStatus());
        }
        RequestStatus to = RequestTransitions.provisionedStatus(entity.getType());
        return advance(entity, to, null, null, externalRef, SYSTEM, null,
                to.name(), "request." + to.name().toLowerCase());
    }

    /**
     * Record a provisioning failure with NO status change (the request stays PROVISIONING for retry). A
     * non-transition event so notify/audit can surface a stuck request; there is no terminal FAILED state
     * in the frozen enums (a future CR if ops needs one).
     */
    @Transactional
    public void provisioningFailed(UUID id, String detail) {
        RequestEntity entity = load(id);
        recordEvent(id, entity.getStatus(), entity.getStatus(), "PROVISIONING_FAILED", SYSTEM, null, detail);
        emit(entity, "request.provisioning_failed");
    }

    // NOTE: get() and timeline() are unguarded engine primitives — they do NOT enforce read ABAC. The
    // Phase 4/5 controllers MUST apply app.read/access.read scope (owners see only their own) before
    // returning these to a caller.

    @Transactional(readOnly = true)
    public RequestEntity get(UUID id) {
        return load(id);
    }

    @Transactional(readOnly = true)
    public List<RequestEventEntity> timeline(UUID id) {
        load(id); // 404 if absent
        return events.findByRequestIdOrderById(id);
    }

    // ---- internals ----

    private RequestEntity load(UUID id) {
        return requests.findById(id).orElseThrow(() -> new NotFoundException("request " + id + " not found"));
    }

    private void checkIfMatch(Integer ifMatchVersion, RequestEntity entity) {
        if (ifMatchVersion != null && ifMatchVersion.intValue() != entity.getVersion()) {
            throw new PreconditionFailedException("stale ETag (expected version " + entity.getVersion() + ")");
        }
    }

    /** Guarded transition + same-tx timeline entry + outbox event; re-reads the fresh row. */
    private RequestEntity advance(RequestEntity entity, RequestStatus to, String approver, String reason,
            String externalRef, String actor, String effectiveRole, String eventType, String outboxEvent) {
        RequestStatus from = entity.getStatus();
        int rows = requests.guardedTransition(entity.getId(), from, to, entity.getVersion(),
                approver, reason, externalRef, Instant.now());
        if (rows == 0) {
            RequestEntity current = requests.findById(entity.getId())
                    .orElseThrow(() -> new NotFoundException("request " + entity.getId() + " not found"));
            if (current.getStatus() != from) {
                throw new ConflictException("state changed to " + current.getStatus()); // lost race / already moved
            }
            throw new PreconditionFailedException("version changed"); // same state, version bumped
        }
        recordEvent(entity.getId(), from, to, eventType, actor, effectiveRole, reason);
        RequestEntity updated = requests.findById(entity.getId()).orElseThrow();
        emit(updated, outboxEvent);
        return updated;
    }

    private void recordEvent(UUID requestId, RequestStatus from, RequestStatus to, String eventType,
            String actor, String effectiveRole, String reason) {
        events.save(new RequestEventEntity(requestId, from, to, eventType, actor, effectiveRole, reason, Instant.now()));
    }

    private void emit(RequestEntity entity, String eventType) {
        outbox.append(AGGREGATE, entity.getId().toString(), eventType, eventPayload(entity));
    }

    private String eventPayload(RequestEntity entity) {
        try {
            return json.writeValueAsString(Map.of(
                    "id", entity.getId().toString(),
                    "type", entity.getType().name(),
                    "status", entity.getStatus().name(),
                    "requester", entity.getRequester()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize outbox payload", e);
        }
    }
}
