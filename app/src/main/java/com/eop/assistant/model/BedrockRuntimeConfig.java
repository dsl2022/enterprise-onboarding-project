package com.eop.assistant.model;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Creates the Bedrock runtime client — but ONLY when {@code eop.assistant.model.provider=bedrock}. On ECS the
 * task role supplies credentials (a to-be-added {@code bedrock:InvokeModel} grant) and {@code AWS_REGION} is set;
 * locally you'd set {@code eop.assistant.model.region}. Gated so a default boot (CI, local, the current 501
 * deployment) never constructs an AWS client or triggers a credential lookup.
 */
@Configuration
@ConditionalOnProperty(prefix = "eop.assistant.model", name = "provider", havingValue = "bedrock")
public class BedrockRuntimeConfig {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(AssistantModelProperties props) {
        var b = BedrockRuntimeClient.builder();
        if (StringUtils.hasText(props.getRegion())) {
            b.region(Region.of(props.getRegion()));
        }
        return b.build();
    }
}
