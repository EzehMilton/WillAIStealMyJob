package com.chikere.jobai.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIModelConfiguration {

    @Bean
    public ChatClient gpt4oChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o")
                        .temperature(0.2)
                        .maxTokens(700)
                        .topP(0.9)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.0)
                        .build())
                .build();
    }

    @Bean
    public ChatClient gpt4oMiniChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .temperature(0.2)
                        .maxTokens(700)
                        .topP(0.9)
                        .frequencyPenalty(0.2)
                        .presencePenalty(0.0)
                        .build())
                .build();
    }
}