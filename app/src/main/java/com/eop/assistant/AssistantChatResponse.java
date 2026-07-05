package com.eop.assistant;

import java.util.List;

/**
 * Response body for {@code POST /assistant/chat} (frozen contract): the assistant's advisory {@code reply} plus
 * zero or more {@link ProposedAction}s the wizard can offer as one-click field fills.
 */
public record AssistantChatResponse(String reply, List<ProposedAction> proposedActions) {
}
