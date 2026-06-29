package com.eop.request;

import com.eop.platform.ConflictException;

/**
 * The per-type state machine (frozen contract + rbac-matrix). Encodes only which moves are <i>legal</i>;
 * the authoritative serialization of who actually makes a legal move is the guarded UPDATE in the
 * repository. Illegal moves raise {@link ConflictException} (→ 409).
 */
final class RequestTransitions {

    private RequestTransitions() {
    }

    /** Resting state at creation: onboarding starts as a DRAFT; an access request is born SUBMITTED. */
    static RequestStatus initialStatus(RequestType type) {
        return type == RequestType.ONBOARDING ? RequestStatus.DRAFT : RequestStatus.SUBMITTED;
    }

    /**
     * Can a (re)submit happen from this state? Onboarding submits from DRAFT (first time) or
     * CHANGES_REQUESTED (resubmit); access only resubmits from CHANGES_REQUESTED (its first submit is the
     * create itself).
     */
    static boolean canSubmitFrom(RequestType type, RequestStatus status) {
        if (type == RequestType.ONBOARDING) {
            return status == RequestStatus.DRAFT || status == RequestStatus.CHANGES_REQUESTED;
        }
        return status == RequestStatus.CHANGES_REQUESTED;
    }

    /** Target state for a decision; a decision is only legal from UNDER_REVIEW. */
    static RequestStatus afterDecision(RequestStatus status, Decision decision) {
        if (status != RequestStatus.UNDER_REVIEW) {
            throw new ConflictException("decision requires UNDER_REVIEW, was " + status);
        }
        return switch (decision) {
            case APPROVE -> RequestStatus.APPROVED;
            case REJECT -> RequestStatus.REJECTED;
            case REQUEST_CHANGES -> RequestStatus.CHANGES_REQUESTED;
        };
    }

    /** Terminal provisioned state by type: onboarding → ACTIVE, access → GRANTED. */
    static RequestStatus provisionedStatus(RequestType type) {
        return type == RequestType.ONBOARDING ? RequestStatus.ACTIVE : RequestStatus.GRANTED;
    }

    /** A payload edit (PATCH) is legal only while the request is still being authored. */
    static boolean canEditFrom(RequestStatus status) {
        return status == RequestStatus.DRAFT || status == RequestStatus.CHANGES_REQUESTED;
    }
}
