package com.eop.request;

/**
 * The union of both contract state enums (OnboardingStatus + AccessStatus). Onboarding rests at
 * {@code ACTIVE}, access at {@code GRANTED}; both share {@code REJECTED}. Legality of a move between
 * statuses is decided per-type by {@link RequestTransitions}.
 */
public enum RequestStatus {
    DRAFT,
    SUBMITTED,
    UNDER_REVIEW,
    CHANGES_REQUESTED,
    REJECTED,
    APPROVED,
    PROVISIONING,
    ACTIVE,
    GRANTED
}
