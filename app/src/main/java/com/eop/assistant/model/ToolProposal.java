package com.eop.assistant.model;

import java.util.Map;

/**
 * A single structured proposal from the model: "I suggest calling tool {@code tool} with these {@code args},
 * because {@code rationale}." This is a <b>proposal</b> — inert until {@link ToolProposalValidator} accepts it
 * and (later, at the Rung-1 service layer) the app maps it to a contract {@code ProposedAction}. The model never
 * executes anything; it only proposes.
 *
 * @param tool      the tool name the model wants to invoke (validated against the allow-list — an unknown or
 *                  malformed name is rejected in Java, never trusted)
 * @param args      the tool arguments the model proposes (opaque here; the Rung-1 service applies per-tool arg
 *                  validation before any use). Never null once validated.
 * @param rationale the model's short human-facing justification (advisory text; length-bounded by the validator)
 */
public record ToolProposal(String tool, Map<String, Object> args, String rationale) {
}
