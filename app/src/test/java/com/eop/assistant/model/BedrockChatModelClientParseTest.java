package com.eop.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the security-critical "treat model output as untrusted" step — {@link BedrockChatModelClient#parseReply}
 * — without a live Bedrock call. Well-formed envelopes parse; everything malformed degrades gracefully (prose
 * with no proposals, or the bad element skipped), never throwing.
 */
class BedrockChatModelClientParseTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parses_a_well_formed_envelope() {
        String text = """
                { "message": "Here is a draft.",
                  "proposals": [
                    { "tool": "draftDescription", "args": { "description": "A billing service." },
                      "rationale": "you asked for a description" } ] }
                """;

        ModelReply reply = BedrockChatModelClient.parseReply(text, json);

        assertThat(reply.message()).isEqualTo("Here is a draft.");
        assertThat(reply.proposals()).singleElement().satisfies(p -> {
            assertThat(p.tool()).isEqualTo("draftDescription");
            assertThat(p.args()).containsEntry("description", "A billing service.");
            assertThat(p.rationale()).isEqualTo("you asked for a description");
        });
    }

    @Test
    void parses_json_wrapped_in_a_markdown_code_fence() {
        String text = "```json\n{ \"message\": \"hi\", \"proposals\": [] }\n```";

        ModelReply reply = BedrockChatModelClient.parseReply(text, json);

        assertThat(reply.message()).isEqualTo("hi");
        assertThat(reply.proposals()).isEmpty();
    }

    @Test
    void non_json_output_degrades_to_prose_with_no_proposals() {
        String text = "I'm sorry, I can only help with the form. (no JSON here)";

        ModelReply reply = BedrockChatModelClient.parseReply(text, json);

        assertThat(reply.message()).isEqualTo(text);
        assertThat(reply.proposals()).isEmpty();
    }

    @Test
    void proposals_not_an_array_is_ignored() {
        String text = "{ \"message\": \"x\", \"proposals\": \"not-an-array\" }";

        ModelReply reply = BedrockChatModelClient.parseReply(text, json);

        assertThat(reply.message()).isEqualTo("x");
        assertThat(reply.proposals()).isEmpty();
    }

    @Test
    void a_malformed_proposal_element_is_skipped_not_thrown() {
        String text = """
                { "proposals": [
                    { "no_tool_here": true },
                    "a bare string",
                    { "tool": "recommendScopes", "args": { "scopes": "read" }, "rationale": "ok" } ] }
                """;

        ModelReply reply = BedrockChatModelClient.parseReply(text, json);

        assertThat(reply.proposals()).singleElement()
                .extracting(ToolProposal::tool).isEqualTo("recommendScopes");
    }

    @Test
    void a_proposal_without_args_defaults_to_empty_args_not_null() {
        String text = "{ \"proposals\": [ { \"tool\": \"draftDescription\", \"rationale\": \"r\" } ] }";

        ModelReply reply = BedrockChatModelClient.parseReply(text, json);

        assertThat(reply.proposals()).singleElement().satisfies(p -> {
            assertThat(p.tool()).isEqualTo("draftDescription");
            assertThat(p.args()).isNotNull().isEmpty();
        });
    }

    @Test
    void blank_output_is_an_empty_reply() {
        ModelReply reply = BedrockChatModelClient.parseReply("   ", json);

        assertThat(reply.message()).isEmpty();
        assertThat(reply.proposals()).isEmpty();
    }
}
