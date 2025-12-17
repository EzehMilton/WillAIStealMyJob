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

    public JobRiskAssessment assessJobRisk(String mode, String profession, String roleSummary) {
        log.info("Assessing job risk for mode: {}, profession: {}", mode, profession);

        String promptTemplate = loadPromptTemplate();

        // Set mode-specific instructions and labels
        String modeInstructions;
        String inputLabel;
        String detailsLabel;

        if ("course".equals(mode)) {
            modeInstructions = "Your task is to assess the career risk associated with pursuing a specific course or degree.\n" +
                    "Analyze the job market prospects, automation risk for typical careers this education leads to,\n" +
                    "and the long-term viability of skills gained over the next 5-10 years.";
            inputLabel = "Course/Degree";
            detailsLabel = "Expected Career Path";
        } else {
            modeInstructions = "Your task is to assess how likely a profession is to be impacted by AI and automation\n" +
                    "over the next 5-10 years.";
            inputLabel = "Profession";
            detailsLabel = "Role Details";
        }

        String prompt = promptTemplate
                .replace("{mode}", mode)
                .replace("{modeInstructions}", modeInstructions)
                .replace("{inputLabel}", inputLabel)
                .replace("{detailsLabel}", detailsLabel)
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
