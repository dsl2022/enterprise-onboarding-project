package com.eop.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eop.assistant.model.AssistantModelProperties;
import com.eop.assistant.model.FakeChatModelClient;
import com.eop.assistant.model.ModelReply;
import com.eop.assistant.model.ToolProposal;
import com.eop.assistant.model.ToolProposalValidator;
import com.eop.platform.UnprocessableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Rung-1 pipeline end-to-end with a distrusted (scripted) model: only allow-listed, well-formed, arg-valid
 * proposals become advisory actions; everything else is dropped. Real validators + prompt/whitelist, fake model.
 */
class AssistantChatServiceTest {

    private final FakeChatModelClient fakeModel = new FakeChatModelClient();
    private final AssistantContextWhitelist whitelist = new AssistantContextWhitelist();
    private final AssistantChatService service = new AssistantChatService(
            fakeModel,
            new ToolProposalValidator(),
            new ToolArgValidators(),
            whitelist,
            new AssistantPromptBuilder(new ObjectMapper()),
            new AssistantModelProperties());

    private static AssistantChatRequest ask(String message) {
        return new AssistantChatRequest(message, null);
    }

    @Test
    void a_benign_allowlisted_proposal_becomes_an_advisory_action() {
        fakeModel.enqueueProposals(FakeChatModelClient.benign());

        var resp = service.chat(ask("help me describe my app"));

        assertThat(resp.proposedActions()).singleElement().satisfies(a -> {
            assertThat(a.tool()).isEqualTo("draftDescription");
            assertThat(a.args()).containsKey("description");
            assertThat(a.requiresApproval()).isFalse();   // Rung 1 = accept-into-field, no server write
            assertThat(a.id()).isNotBlank();
        });
    }

    @Test
    void off_allowlist_and_injected_proposals_are_dropped() {
        fakeModel.enqueueProposals(
                FakeChatModelClient.offAllowList(), FakeChatModelClient.injectedToolName());

        var resp = service.chat(ask("do something sneaky"));

        assertThat(resp.proposedActions()).isEmpty();
    }

    @Test
    void an_allowlisted_tool_with_bad_args_is_dropped_by_the_second_gate() {
        // draftDescription is allow-listed, but its args lack the required "description"
        fakeModel.enqueueProposals(new ToolProposal("draftDescription", Map.of(), "oops"));

        var resp = service.chat(ask("describe it"));

        assertThat(resp.proposedActions()).isEmpty();
    }

    @Test
    void recommend_scopes_are_filtered_and_deduped_in_java() {
        fakeModel.enqueueProposals(new ToolProposal("recommendScopes",
                Map.of("scopes", List.of("read", "read", "bad\nvalue")), "suggested"));

        var resp = service.chat(ask("what scopes?"));

        @SuppressWarnings("unchecked")
        List<String> scopes = (List<String>) resp.proposedActions().get(0).args().get("scopes");
        assertThat(scopes).containsExactly("read"); // dup collapsed, newline-bearing token rejected
    }

    @Test
    void redirect_uris_get_an_authoritative_java_verdict() {
        fakeModel.enqueueProposals(new ToolProposal("validateRedirectUris",
                Map.of("uris", List.of("https://app.example/cb", "http://evil.example/cb", "not a uri")),
                "checking"));

        var resp = service.chat(ask("are these ok?"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
                (List<Map<String, Object>>) resp.proposedActions().get(0).args().get("results");
        assertThat(results).hasSize(3);
        assertThat(results.get(0)).containsEntry("valid", true);   // https
        assertThat(results.get(1)).containsEntry("valid", false);  // http non-localhost
        assertThat(results.get(2)).containsEntry("valid", false);  // unparseable
    }

    @Test
    void blank_message_is_422() {
        assertThatThrownBy(() -> service.chat(ask("   ")))
                .isInstanceOf(UnprocessableException.class);
        assertThatThrownBy(() -> service.chat(new AssistantChatRequest(null, null)))
                .isInstanceOf(UnprocessableException.class);
    }

    @Test
    void the_reply_prose_passes_through_and_no_proposals_is_fine() {
        fakeModel.enqueue(ModelReply.plain("I can only help draft the form fields."));

        var resp = service.chat(ask("what's the weather?"));

        assertThat(resp.reply()).isEqualTo("I can only help draft the form fields.");
        assertThat(resp.proposedActions()).isEmpty();
    }

    @Test
    void only_whitelisted_context_reaches_the_model_prompt() {
        var ctx = Map.<String, Object>of(
                "appName", "Billing", "secretApiKey", "sk-should-be-dropped");
        service.chat(new AssistantChatRequest("hi", ctx));

        String systemPrompt = fakeModel.lastRequest().systemPrompt();
        assertThat(systemPrompt).contains("Billing");
        assertThat(systemPrompt).doesNotContain("sk-should-be-dropped");
    }
}
