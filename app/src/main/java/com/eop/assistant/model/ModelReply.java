package com.eop.assistant.model;

import java.util.List;

/**
 * The model's structured answer: an optional free-text {@code message} plus zero or more {@link ToolProposal}s.
 * Adapters produce this by parsing the model's (untrusted) output; a response that doesn't conform degrades to
 * {@link #plain(String)} — message preserved, <b>no</b> proposals — so malformed/injected output can never
 * smuggle an action through. Downstream, {@link ToolProposalValidator} still re-checks every proposal.
 *
 * @param message   optional assistant prose (maps to the contract's {@code reply}); may be null/blank
 * @param proposals structured proposals; never null (empty when the model proposed nothing or the reply was
 *                  unparseable)
 */
public record ModelReply(String message, List<ToolProposal> proposals) {

    public ModelReply {
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
    }

    /** A reply carrying only prose — no proposals. The safe degradation target for unparseable model output. */
    public static ModelReply plain(String message) {
        return new ModelReply(message, List.of());
    }
}
