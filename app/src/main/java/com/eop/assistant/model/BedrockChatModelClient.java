package com.eop.assistant.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * The Amazon Bedrock adapter (Claude via the vendor-neutral Converse API). Active only when
 * {@code eop.assistant.model.provider=bedrock}. IAM-native: it authenticates with the ECS task role
 * ({@code bedrock:InvokeModel}), so there is <b>no stored API key</b> — consistent with the project's
 * zero-stored-credentials posture. Bedrock does not retain or train on request content.
 *
 * <p><b>Model output is untrusted.</b> The model is instructed (by the caller's system prompt) to reply with a
 * strict JSON envelope; this adapter parses that envelope defensively via {@link #parseReply(String, ObjectMapper)}
 * and degrades any malformed/non-conforming output to a proposal-free {@link ModelReply} — a bad reply can never
 * throw into control flow or smuggle an action through. {@link ToolProposalValidator} still re-checks whatever
 * proposals survive.
 */
@Component
@ConditionalOnProperty(prefix = "eop.assistant.model", name = "provider", havingValue = "bedrock")
public class BedrockChatModelClient implements ChatModelClient {

    private static final Logger log = LoggerFactory.getLogger(BedrockChatModelClient.class);

    private final BedrockRuntimeClient bedrock;
    private final AssistantModelProperties props;
    private final ObjectMapper json;

    public BedrockChatModelClient(BedrockRuntimeClient bedrock, AssistantModelProperties props, ObjectMapper json) {
        this.bedrock = bedrock;
        this.props = props;
        this.json = json;
    }

    @Override
    public ModelReply complete(ModelRequest request) {
        String modelId = StringUtils.hasText(request.modelIdOverride())
                ? request.modelIdOverride()
                : props.getModelId();
        int maxTokens = request.maxOutputTokens() > 0 ? request.maxOutputTokens() : props.getMaxOutputTokens();

        ConverseResponse response = bedrock.converse(b -> b
                .modelId(modelId)
                .system(SystemContentBlock.fromText(request.systemPrompt()))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(request.userMessage()))
                        .build())
                .inferenceConfig(cfg -> cfg.maxTokens(maxTokens).temperature(0.2f)));

        return parseReply(extractText(response), json);
    }

    /** Concatenate the text blocks of the assistant message; tolerant of a missing/empty output. */
    private static String extractText(ConverseResponse response) {
        if (response.output() == null || response.output().message() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.output().message().content()) {
            if (block.text() != null) {
                sb.append(block.text());
            }
        }
        return sb.toString();
    }

    /**
     * Parse the model's JSON envelope into a {@link ModelReply}, defensively. Expected shape:
     * <pre>{ "message": "…", "proposals": [ { "tool": "…", "args": {…}, "rationale": "…" } ] }</pre>
     * Any deviation (not JSON, wrong types, non-object proposal, missing tool) degrades gracefully: unparseable
     * output becomes {@link ModelReply#plain(String)} (prose, no proposals); a malformed element is skipped, not
     * thrown. Package-private so the parsing — the security-critical "treat output as untrusted" step — is unit
     * tested without a live Bedrock call.
     */
    static ModelReply parseReply(String text, ObjectMapper json) {
        if (!StringUtils.hasText(text)) {
            return ModelReply.plain("");
        }
        JsonNode root;
        try {
            root = json.readTree(stripCodeFences(text));
        } catch (Exception notJson) {
            return ModelReply.plain(text); // whole reply is prose; nothing actionable
        }
        if (root == null || !root.isObject()) {
            return ModelReply.plain(text);
        }

        String message = root.hasNonNull("message") ? root.get("message").asText() : null;

        List<ToolProposal> proposals = new ArrayList<>();
        JsonNode arr = root.get("proposals");
        if (arr != null && arr.isArray()) {
            for (JsonNode el : arr) {
                ToolProposal p = toProposal(el, json);
                if (p != null) {
                    proposals.add(p);
                }
            }
        }
        return new ModelReply(message, proposals);
    }

    /** One proposal element → {@link ToolProposal}, or null if it isn't a usable object with a tool name. */
    private static ToolProposal toProposal(JsonNode el, ObjectMapper json) {
        if (el == null || !el.isObject() || !el.hasNonNull("tool")) {
            return null;
        }
        String tool = el.get("tool").asText();
        String rationale = el.hasNonNull("rationale") ? el.get("rationale").asText() : null;
        Map<String, Object> args = new LinkedHashMap<>();
        JsonNode argsNode = el.get("args");
        if (argsNode != null && argsNode.isObject()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.convertValue(argsNode, Map.class);
            args = parsed;
        }
        return new ToolProposal(tool, args, rationale);
    }

    /** Strip a leading ```/```json fence and trailing ``` that models often wrap JSON in. */
    private static String stripCodeFences(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline >= 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
        }
        return t.strip();
    }
}
