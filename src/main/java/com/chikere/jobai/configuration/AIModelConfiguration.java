package com.chikere.jobai.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIModelConfiguration {

    /**
     * Main reasoning client for risk scoring and other important assessment logic.
     */
    @Bean
    public ChatClient gpt54ChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-5.4")
                        .temperature(0.2)
                        .build())
                .build();
    }

    /**
     * Faster, cheaper client for extraction, normalization, and lightweight summaries.
     */
    @Bean
    public ChatClient gpt54MiniChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-5.4-mini")
                        .temperature(0.2)
                        .build())
                .build();
    }

    /**
     * Dedicated client for premium report generation.
     * Slightly higher temperature gives more natural writing,
     * while still staying fairly controlled.
     */
    @Bean
    public ChatClient gpt54ReportChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-5.4")
                        .temperature(0.6)
                        .build())
                .build();
    }
}