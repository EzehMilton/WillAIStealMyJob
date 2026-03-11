package com.chikere.jobai.service;

import com.chikere.jobai.model.JobRiskAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class JobAiService {

    private final ChatClient gpt54ChatClient;
    private final ChatClient gpt5MiniChatClient;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cached instructions loaded at startup
    private final String professionInstructions;
    private final String courseInstructions;

    public JobAiService(ChatClient gpt54ChatClient,
                        ChatClient gpt5MiniChatClient,
                        ResourceLoader resourceLoader) {
        this.gpt54ChatClient = gpt54ChatClient;
        this.gpt5MiniChatClient = gpt5MiniChatClient;
        this.resourceLoader = resourceLoader;

        this.professionInstructions = loadResourceFile("classpath:prompts/profession-instructions.txt");
        this.courseInstructions = loadResourceFile("classpath:prompts/course-instructions.txt");
        log.info("Loaded prompt instructions for profession and course modes");
    }

    public JobRiskAssessment assessJobRisk(String mode, String profession, String roleSummary) {
        log.info("Assessing job risk for mode: {}, profession: {}", mode, profession);

        String promptTemplate = loadResourceFile("classpath:prompts/jobai.txt");

        // Set mode-specific instructions and labels
        String modeInstructions;
        String inputLabel;
        String detailsLabel;

        if ("course".equals(mode)) {
            modeInstructions = courseInstructions;
            inputLabel = "Course/Degree";
            detailsLabel = "Expected Career Path";
        } else {
            modeInstructions = professionInstructions;
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

        // Use mini model for course/degree assessments, full model for profession assessments
        ChatClient selectedChatClient = "course".equals(mode) ? gpt5MiniChatClient : gpt54ChatClient;
        log.info("Using model: {}", "course".equals(mode) ? "gpt-54-mini" : "gpt-54");

        String response = selectedChatClient.prompt(prompt).call().content();
        String cleanedResponse = cleanJsonResponse(response);
        log.debug("Received assessment response: {}", cleanedResponse);

        try {
            return objectMapper.readValue(cleanedResponse, JobRiskAssessment.class);
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", response, e);
            throw new RuntimeException("Failed to parse AI response", e);
        }
    }

    private String cleanJsonResponse(String response) {
        if (response == null) {
            return null;
        }

        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            if (firstNewline != -1) {
                cleaned = cleaned.substring(firstNewline + 1);
            }
            int lastFence = cleaned.lastIndexOf("```");
            if (lastFence != -1) {
                cleaned = cleaned.substring(0, lastFence);
            }
            cleaned = cleaned.trim();
        }

        int objectStart = cleaned.indexOf('{');
        int arrayStart = cleaned.indexOf('[');
        int start = -1;
        int end = -1;

        if (objectStart != -1 && (arrayStart == -1 || objectStart < arrayStart)) {
            start = objectStart;
            end = cleaned.lastIndexOf('}');
        } else if (arrayStart != -1) {
            start = arrayStart;
            end = cleaned.lastIndexOf(']');
        }

        if (start != -1 && end != -1 && end > start) {
            return cleaned.substring(start, end + 1).trim();
        }

        return cleaned;
    }

    private String loadResourceFile(String resourcePath) {
        try {
            Resource resource = resourceLoader.getResource(resourcePath);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load resource file: {}", resourcePath, e);
            throw new RuntimeException("Failed to load resource file: " + resourcePath, e);
        }
    }
}
