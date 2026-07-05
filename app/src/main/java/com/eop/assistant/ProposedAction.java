package com.eop.assistant;

import java.util.Map;

/**
 * One advisory suggestion returned to the wizard (frozen contract). At Rung 1 every proposed action is a
 * <b>field fill</b> the user accepts client-side, so {@code requiresApproval} is always {@code false} — there is
 * no server-side write to approve (that's Rung 3, and {@code /assistant/actions/{id}/approve} stays 501). The
 * {@code tool} is always one of the four Rung-1 tools (the model's proposal was re-validated in Java), and
 * {@code args} have passed per-tool validation.
 */
public record ProposedAction(String id, String tool, Map<String, Object> args, boolean requiresApproval) {
}
