package com.eop.assistant;

import com.eop.assistant.model.AssistantModelProperties;
import com.eop.assistant.model.ChatModelClient;
import com.eop.assistant.model.ModelReply;
import com.eop.assistant.model.ModelRequest;
import com.eop.assistant.model.Rung1Tools;
import com.eop.assistant.model.ToolProposal;
import com.eop.assistant.model.ToolProposalValidator;
import com.eop.platform.UnprocessableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Rung-1 advisory form-fill. Orchestrates one chat turn with all authority in Java around a distrusted model:
 * whitelist the wizard context, build a trusted prompt, call the model port, then re-validate its proposals
 * against the Rung-1 allow-list ({@link ToolProposalValidator}) and per-tool arg rules ({@link ToolArgValidators})
 * before mapping the survivors to advisory {@link ProposedAction}s. It never writes governed state, retrieves
 * cross-user data, or calls Graph — so every action is {@code requiresApproval=false} (a client-side field fill).
 *
 * <p>Created only when {@code eop.assistant.enabled=true} (and a {@link ChatModelClient} exists); otherwise the
 * controller has no service to delegate to and {@code /assistant/chat} keeps returning 501.
 */
@Service
@ConditionalOnProperty(prefix = "eop.assistant", name = "enabled", havingValue = "true")
public class AssistantChatService {

    private static final Logger log = LoggerFactory.getLogger(AssistantChatService.class);

    private final ChatModelClient model;
    private final ToolProposalValidator proposalValidator;
    private final ToolArgValidators argValidators;
    private final AssistantContextWhitelist whitelist;
    private final AssistantPromptBuilder promptBuilder;
    private final AssistantModelProperties modelProps;

    public AssistantChatService(ChatModelClient model, ToolProposalValidator proposalValidator,
            ToolArgValidators argValidators, AssistantContextWhitelist whitelist,
            AssistantPromptBuilder promptBuilder, AssistantModelProperties modelProps) {
        this.model = model;
        this.proposalValidator = proposalValidator;
        this.argValidators = argValidators;
        this.whitelist = whitelist;
        this.promptBuilder = promptBuilder;
        this.modelProps = modelProps;
    }

    public AssistantChatResponse chat(AssistantChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new UnprocessableException("message is required");
        }

        Map<String, Object> context = whitelist.filter(request.context());
        String systemPrompt = promptBuilder.systemPrompt(context);
        ModelRequest modelRequest = ModelRequest.of(
                systemPrompt, request.message().strip(), modelProps.getMaxOutputTokens());

        ModelReply reply = model.complete(modelRequest);

        ToolProposalValidator.Result validated =
                proposalValidator.validate(reply.proposals(), Rung1Tools.ALLOW_LIST);
        if (!validated.rejected().isEmpty()) {
            // Distrusted-model boundary firing — worth visibility, but no transcript to the audit chain (Q2).
            log.warn("assistant: dropped {} model proposal(s) failing allow-list/shape: {}",
                    validated.rejected().size(),
                    validated.rejected().stream().map(ToolProposalValidator.Rejection::reason).toList());
        }

        List<ProposedAction> actions = new ArrayList<>();
        for (ToolProposal p : validated.accepted()) {
            argValidators.validate(p.tool(), p.args()).ifPresentOrElse(
                    safeArgs -> actions.add(
                            new ProposedAction(UUID.randomUUID().toString(), p.tool(), safeArgs, false)),
                    () -> log.warn("assistant: dropped {} proposal — args failed per-tool validation", p.tool()));
        }

        return new AssistantChatResponse(reply.message(), List.copyOf(actions));
    }
}
