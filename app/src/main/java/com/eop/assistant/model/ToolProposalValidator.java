package com.eop.assistant.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The deterministic authority seam. Every {@link ToolProposal} the model emits is re-validated here, in Java,
 * against a caller-supplied allow-list before anything downstream may act on it. This is the concrete embodiment
 * of "authority lives in Java, not in the model" — the model can propose whatever it likes (including, via a
 * prompt-injection, a tool it was never offered or a malformed/oversized payload); this class is what makes such
 * a proposal <b>inert</b>.
 *
 * <p>Deliberately allow-list-driven, not filter/deny-list-driven: we don't try to detect bad proposals, we only
 * pass ones that are provably on the list and well-formed. Unknown, malformed, or oversized proposals are
 * rejected with a reason (for logging/observability), never silently coerced.
 *
 * <p>The allow-list is a parameter (see {@link Rung1Tools#ALLOW_LIST} for the Rung-1 set) so the seam is
 * decoupled from any one rung's tool universe.
 */
@Component
public class ToolProposalValidator {

    /** Tool names are simple identifiers — reject anything with whitespace, punctuation, or injection glyphs. */
    private static final Pattern SAFE_TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");

    static final int MAX_ARGS = 32;
    static final int MAX_RATIONALE_CHARS = 2_000;

    /**
     * Partition the proposals into accepted (on the allow-list and well-formed) and rejected (with a reason).
     * Null-safe: a null list or null elements are handled, never thrown.
     */
    public Result validate(List<ToolProposal> proposals, Set<String> allowList) {
        List<ToolProposal> accepted = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        if (proposals != null) {
            for (ToolProposal p : proposals) {
                String reason = rejectionReason(p, allowList);
                if (reason == null) {
                    accepted.add(p);
                } else {
                    rejected.add(new Rejection(p, reason));
                }
            }
        }
        return new Result(List.copyOf(accepted), List.copyOf(rejected));
    }

    /** @return null if the proposal is acceptable, else a short machine/log-friendly reason it was rejected. */
    private String rejectionReason(ToolProposal p, Set<String> allowList) {
        if (p == null) {
            return "null proposal";
        }
        String tool = p.tool();
        if (tool == null || tool.isBlank()) {
            return "missing tool name";
        }
        if (!SAFE_TOOL_NAME.matcher(tool).matches()) {
            return "malformed tool name";
        }
        if (allowList == null || !allowList.contains(tool)) {
            return "tool not in allow-list: " + tool;
        }
        if (p.args() == null) {
            return "missing args";
        }
        if (p.args().size() > MAX_ARGS) {
            return "too many args (" + p.args().size() + " > " + MAX_ARGS + ")";
        }
        if (p.rationale() != null && p.rationale().length() > MAX_RATIONALE_CHARS) {
            return "rationale too long (" + p.rationale().length() + " > " + MAX_RATIONALE_CHARS + ")";
        }
        return null;
    }

    /**
     * Outcome of validating a batch. {@code accepted} are safe to hand downstream; {@code rejected} carry a
     * reason for logging. Both lists are immutable.
     */
    public record Result(List<ToolProposal> accepted, List<Rejection> rejected) {

        /** True when nothing was rejected — every proposal was on the allow-list and well-formed. */
        public boolean clean() {
            return rejected.isEmpty();
        }
    }

    /** A single rejected proposal plus the deterministic reason it failed. */
    public record Rejection(ToolProposal proposal, String reason) {
    }
}
