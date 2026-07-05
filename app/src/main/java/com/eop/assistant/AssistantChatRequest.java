package com.eop.assistant;

import java.util.Map;

/**
 * Request body for {@code POST /assistant/chat} (frozen contract). {@code message} is the user's turn;
 * {@code context} is the wizard's in-progress fields (untrusted — the service whitelists it before it reaches
 * the model). Both are treated as data, never as instructions.
 */
public record AssistantChatRequest(String message, Map<String, Object> context) {
}
