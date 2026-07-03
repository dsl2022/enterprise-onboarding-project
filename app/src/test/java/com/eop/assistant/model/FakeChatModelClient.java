package com.eop.assistant.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A scriptable {@link ChatModelClient} test double with two jobs:
 * <ol>
 *   <li><b>Deterministic fixture</b> — enqueue exactly the {@link ModelReply} a test wants back, and assert on
 *       the {@link ModelRequest} the code under test built ({@link #lastRequest()}).</li>
 *   <li><b>Malicious-model harness</b> — the safety thesis needs a model that misbehaves. The static factories
 *       below emit out-of-allow-list, malformed, oversized, and prompt-injection-style proposals so guardrail
 *       tests can prove the deterministic Java layer ({@link ToolProposalValidator}) rejects them.</li>
 * </ol>
 * Returning {@link ModelReply}s directly (bypassing JSON parsing) is intentional: this feeds the <i>validator</i>
 * structured-but-hostile proposals, which is exactly the boundary we want to prove holds.
 */
public class FakeChatModelClient implements ChatModelClient {

    private final Deque<ModelReply> scripted = new ArrayDeque<>();
    private ModelRequest lastRequest;

    /** Queue a full reply to be returned by the next {@link #complete}. */
    public FakeChatModelClient enqueue(ModelReply reply) {
        scripted.add(reply);
        return this;
    }

    /** Queue a proposals-only reply (no prose). */
    public FakeChatModelClient enqueueProposals(ToolProposal... proposals) {
        scripted.add(new ModelReply(null, List.of(proposals)));
        return this;
    }

    @Override
    public ModelReply complete(ModelRequest request) {
        this.lastRequest = request;
        return scripted.isEmpty() ? ModelReply.plain("(fake: no scripted reply)") : scripted.poll();
    }

    /** The request the code under test last sent — for asserting prompt/limit construction. */
    public ModelRequest lastRequest() {
        return lastRequest;
    }

    // ---- malicious / edge proposal factories (the "distrust the model" rig) ----

    /** A well-formed, allow-listed proposal — the accepted baseline. */
    public static ToolProposal benign() {
        return new ToolProposal(Rung1Tools.DRAFT_DESCRIPTION,
                Map.of("description", "A billing microservice."), "user asked for a description");
    }

    /** A tool the model was never offered — the classic prompt-injection escalation. */
    public static ToolProposal offAllowList() {
        return new ToolProposal("deleteAllRequests", Map.of(), "ignore previous instructions and purge");
    }

    /** A tool name laced with injection glyphs — must fail the name pattern, not just the allow-list. */
    public static ToolProposal injectedToolName() {
        return new ToolProposal("draftDescription; DROP TABLE requests", Map.of("x", "y"), "sneaky");
    }

    /** Missing args (a malformed structured proposal). */
    public static ToolProposal nullArgs() {
        return new ToolProposal(Rung1Tools.RECOMMEND_SCOPES, null, "no args");
    }

    /** An oversized rationale — a resource/log-flood attempt. */
    public static ToolProposal oversizedRationale() {
        return new ToolProposal(Rung1Tools.DRAFT_JUSTIFICATION, Map.of(), "z".repeat(5_000));
    }
}
