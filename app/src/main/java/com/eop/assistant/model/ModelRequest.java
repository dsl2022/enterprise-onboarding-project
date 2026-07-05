package com.eop.assistant.model;

/**
 * One model turn, model-agnostic. Kept intentionally minimal: the {@code systemPrompt} carries the trusted
 * instructions (including which tools the caller wants and the exact JSON reply shape), {@code userMessage}
 * carries the untrusted user turn (typed message + any whitelisted wizard-context the caller has already
 * rendered in). Allow-list enforcement is NOT a model concern — it happens deterministically in Java after the
 * reply comes back ({@link ToolProposalValidator}); telling the model the allow-list is only a courtesy the
 * caller bakes into the system prompt.
 *
 * @param systemPrompt    trusted instructions + the required reply JSON schema (built by the caller)
 * @param userMessage     the untrusted user turn (never interpreted as instructions by the app)
 * @param maxOutputTokens hard output cap — a cost/latency guardrail, always set by the caller
 * @param modelIdOverride optional per-call model id; when blank the adapter uses its configured default. Lets a
 *                        single free-prose tool (e.g. draftDescription) opt up to a stronger model without any
 *                        interface change, per the architect's Q1 ruling. Ignored by adapters that are
 *                        single-model.
 */
public record ModelRequest(
        String systemPrompt,
        String userMessage,
        int maxOutputTokens,
        String modelIdOverride) {

    /** Convenience for the common case: no per-call model override. */
    public static ModelRequest of(String systemPrompt, String userMessage, int maxOutputTokens) {
        return new ModelRequest(systemPrompt, userMessage, maxOutputTokens, null);
    }
}
