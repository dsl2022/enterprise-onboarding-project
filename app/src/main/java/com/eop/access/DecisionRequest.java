package com.eop.access;

import com.eop.request.Decision;

/** Frozen contract {@code DecisionBody} (access-local to respect the module boundary). */
public record DecisionRequest(Decision decision, String reason) {
}
