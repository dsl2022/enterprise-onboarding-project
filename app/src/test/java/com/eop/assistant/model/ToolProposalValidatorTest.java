package com.eop.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The safety thesis, proven: whatever the (distrusted) model proposes, only allow-listed, well-formed proposals
 * survive the deterministic Java layer. Hostile proposals are sourced from {@link FakeChatModelClient}'s
 * malicious-model rig, so the boundary is exercised end-to-end (fake model → validator), not with hand-rolled
 * inputs.
 */
class ToolProposalValidatorTest {

    private final ToolProposalValidator validator = new ToolProposalValidator();
    private final Set<String> allow = Rung1Tools.ALLOW_LIST;

    @Test
    void accepts_allowlisted_wellformed_proposal() {
        var result = validator.validate(List.of(FakeChatModelClient.benign()), allow);

        assertThat(result.clean()).isTrue();
        assertThat(result.accepted()).singleElement()
                .extracting(ToolProposal::tool).isEqualTo(Rung1Tools.DRAFT_DESCRIPTION);
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    void rejects_tool_not_in_allow_list() {
        var result = validator.validate(List.of(FakeChatModelClient.offAllowList()), allow);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement()
                .extracting(ToolProposalValidator.Rejection::reason).asString().contains("not in allow-list");
    }

    @Test
    void rejects_injected_tool_name_on_the_pattern_not_just_the_list() {
        var result = validator.validate(List.of(FakeChatModelClient.injectedToolName()), allow);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement()
                .extracting(ToolProposalValidator.Rejection::reason).isEqualTo("malformed tool name");
    }

    @Test
    void rejects_missing_args() {
        var result = validator.validate(List.of(FakeChatModelClient.nullArgs()), allow);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement()
                .extracting(ToolProposalValidator.Rejection::reason).isEqualTo("missing args");
    }

    @Test
    void rejects_blank_tool_name() {
        var result = validator.validate(List.of(new ToolProposal("  ", Map.of(), "x")), allow);

        assertThat(result.rejected()).singleElement()
                .extracting(ToolProposalValidator.Rejection::reason).isEqualTo("missing tool name");
    }

    @Test
    void rejects_oversized_rationale() {
        var result = validator.validate(List.of(FakeChatModelClient.oversizedRationale()), allow);

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).singleElement()
                .extracting(ToolProposalValidator.Rejection::reason).asString().contains("rationale too long");
    }

    @Test
    void rejects_too_many_args() {
        var manyArgs = new java.util.HashMap<String, Object>();
        for (int i = 0; i <= ToolProposalValidator.MAX_ARGS; i++) {
            manyArgs.put("k" + i, i);
        }
        var result = validator.validate(
                List.of(new ToolProposal(Rung1Tools.RECOMMEND_SCOPES, manyArgs, "x")), allow);

        assertThat(result.rejected()).singleElement()
                .extracting(ToolProposalValidator.Rejection::reason).asString().contains("too many args");
    }

    @Test
    void partitions_a_mixed_batch_keeping_only_the_safe_one() {
        var batch = List.of(
                FakeChatModelClient.benign(),          // accepted
                FakeChatModelClient.offAllowList(),    // rejected: not on list
                FakeChatModelClient.injectedToolName() // rejected: malformed name
        );

        var result = validator.validate(batch, allow);

        assertThat(result.clean()).isFalse();
        assertThat(result.accepted()).hasSize(1);
        assertThat(result.rejected()).hasSize(2);
    }

    @Test
    void null_proposal_list_is_a_clean_empty_result() {
        var result = validator.validate(null, allow);

        assertThat(result.clean()).isTrue();
        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).isEmpty();
    }

    @Test
    void empty_allow_list_rejects_everything() {
        var result = validator.validate(List.of(FakeChatModelClient.benign()), Set.of());

        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).hasSize(1);
    }
}
