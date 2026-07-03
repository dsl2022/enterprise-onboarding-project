package com.eop.assistant.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Model-backend config for the assistant. All values come from the environment; nothing is committed. The
 * {@code provider} switch keeps the real (AWS-dependent) client out of the bean graph unless explicitly turned
 * on — mirrors the {@code wif.enabled} pattern, so a plain CI/local boot creates no Bedrock client and does no
 * AWS credential lookup. Nothing is wired to a model in this PR (the controller still returns 501); this is the
 * reversible seam the Rung-1 build will switch on.
 */
@ConfigurationProperties(prefix = "eop.assistant.model")
public class AssistantModelProperties {

    /**
     * Which {@link ChatModelClient} adapter to create: {@code "bedrock"} for the real Amazon Bedrock client.
     * Blank/anything else = no real client bean (tests supply a fake). Default blank, so the app never reaches
     * for AWS unless asked.
     */
    private String provider = "";

    /**
     * Bedrock model id (Converse API). Default is a Haiku-tier Claude — Rung 1 is narrow, latency- and
     * cost-sensitive form-fill, so Haiku is the right default (architect Q1), not a compromise. Override per
     * env; confirm the model is access-enabled in the account/region first (Bedrock model access is opt-in
     * per-model-per-region). A single free-prose tool can opt up to Sonnet via {@link ModelRequest#modelIdOverride()}.
     */
    private String modelId = "anthropic.claude-3-5-haiku-20241022-v1:0";

    /** AWS region for Bedrock. Blank = default provider chain ({@code AWS_REGION}); dev target is us-east-1. */
    private String region = "";

    /** Default output-token cap when a request doesn't set one — a cost/latency guardrail. */
    private int maxOutputTokens = 1_024;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(int maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }
}
