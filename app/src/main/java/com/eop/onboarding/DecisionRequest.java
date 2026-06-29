package com.eop.onboarding;

import com.eop.request.Decision;

/** Frozen contract {@code DecisionBody}. */
public record DecisionRequest(Decision decision, String reason) {
}
