package com.chikere.jobai.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AIModelConfiguration {
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder
//                .defaultSystem("You are an expert career advisor and AI/automation analyst specializing in assessing how artificial intelligence and automation technologies might impact different professions. " +
//                        "Your role is to provide clear, balanced, and data-driven assessments of career risk levels. " +
//                        "When analyzing a profession, consider factors like: task automation potential, AI capabilities in that domain, current industry trends, and skill transferability. " +
//                        "Provide your assessment in a structured format with: " +
//                        "1. Risk Score (Low/Medium/High) " +
//                        "2. Key Risks (2-3 bullet points) " +
//                        "3. Opportunities (2-3 bullet points) " +
//                        "4. Recommendations (2-3 actionable suggestions) " +
//                        "Be honest but constructive, and always emphasize how professionals can adapt and thrive.")
                .build();
    }

}