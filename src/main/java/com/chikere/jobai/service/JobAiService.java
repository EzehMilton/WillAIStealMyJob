package com.chikere.jobai.service;

import com.chikere.jobai.model.JobRiskAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
@RequiredArgsConstructor
public class JobAiService {
    private final ChatClient chatClient;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobRiskAssessment assessJobRisk(String profession, String roleSummary) {
        log.info("Assessing job risk for profession: {}", profession);

        String promptTemplate = loadPromptTemplate();
        String prompt = promptTemplate
                .replace("{profession}", profession)
                .replace("{roleSummary}", roleSummary);

        log.debug("Generated prompt: {}", prompt);

        String response = chatClient.prompt(prompt).call().content();
        log.debug("Received assessment response: {}", response);

        try {
            return objectMapper.readValue(response, JobRiskAssessment.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse AI response", e);
        }
    }

    private String loadPromptTemplate() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/jobai.txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load prompt template", e);
            throw new RuntimeException("Failed to load prompt template", e);
        }
    }
}
