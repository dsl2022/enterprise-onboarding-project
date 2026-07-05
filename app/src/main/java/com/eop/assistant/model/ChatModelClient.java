package com.eop.assistant.model;

/**
 * The assistant's model seam — the ONE place the app talks to an LLM. Everything else in the assistant depends
 * on this port, never on a concrete model/vendor SDK, so "which model, which host" stays a late-binding config
 * decision (Bedrock today; an OpenAI-compatible endpoint tomorrow — no rewrite).
 *
 * <p><b>The model is a distrusted component.</b> A {@link ModelReply} is a <i>proposal</i>, not an instruction:
 * it carries structured {@link ToolProposal}s that the deterministic Java layer ({@link ToolProposalValidator})
 * validates against an allow-list before anything acts on them. Authority lives in Java, not in the model — the
 * port deliberately returns structure, never raw text to be trusted or executed.
 *
 * <p>No implementation writes governed state, provisions, or calls Graph. This interface is advisory by
 * construction (Rung&nbsp;1 of the autonomy ladder; see {@code docs/assistant-feature-design-and-guardrails.md}).
 */
public interface ChatModelClient {

    /**
     * Send one request to the model and return its structured proposal. Implementations MUST treat the model's
     * output as untrusted: a malformed or non-conforming response becomes an empty-proposal {@link ModelReply}
     * (see {@link ModelReply#plain(String)}), never an exception that leaks model text into control flow.
     */
    ModelReply complete(ModelRequest request);
}
