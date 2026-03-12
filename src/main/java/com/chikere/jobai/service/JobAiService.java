package com.chikere.jobai.service;

import com.chikere.jobai.model.JobRiskAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

@Service
@Slf4j
public class JobAiService {

    private final ChatClient gpt54ChatClient;
    private final ChatClient gpt5MiniChatClient;
    private final ResourceLoader resourceLoader;
    private final boolean useDummyMode;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cached instructions loaded at startup
    private final String professionInstructions;
    private final String courseInstructions;

    public JobAiService(ChatClient gpt54ChatClient,
                        ChatClient gpt5MiniChatClient,
                        ResourceLoader resourceLoader,
                        @Value("${app.ai.use-dummy:false}") boolean useDummyMode) {
        this.gpt54ChatClient = gpt54ChatClient;
        this.gpt5MiniChatClient = gpt5MiniChatClient;
        this.resourceLoader = resourceLoader;
        this.useDummyMode = useDummyMode;

        this.professionInstructions = loadResourceFile("classpath:prompts/profession-instructions.txt");
        this.courseInstructions = loadResourceFile("classpath:prompts/course-instructions.txt");
        log.info("Loaded prompt instructions for profession and course modes");
        if (this.useDummyMode) {
            log.info("JobAiService is running in dummy mode. No external AI model calls will be made.");
        }
    }

    public JobRiskAssessment assessJobRisk(String mode, String profession, String roleSummary) {
        log.info("Assessing job risk for mode: {}, profession: {}", mode, profession);

        if (useDummyMode) {
            return buildDummyAssessment(mode, profession, roleSummary);
        }

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

    private JobRiskAssessment buildDummyAssessment(String mode, String profession, String roleSummary) {
        String normalizedMode = mode == null ? "profession" : mode.trim().toLowerCase(Locale.ROOT);
        String normalizedProfession = profession == null ? "Unknown" : profession.trim();
        String normalizedRoleSummary = roleSummary == null ? "" : roleSummary.trim();
        String content = normalizedRoleSummary.toLowerCase(Locale.ROOT);

        double score = (Math.floorMod(
                Objects.hash(normalizedMode, normalizedProfession.toLowerCase(Locale.ROOT), content), 71
        ) / 10.0) + 1.5; // 1.5 - 8.5
        score += scoreAdjustment(content);
        score = Math.max(0.5, Math.min(9.5, Math.round(score * 10.0) / 10.0));

        String riskLevel = score <= 2 ? "Low" : (score <= 6 ? "Moderate" : "High");

        JobRiskAssessment assessment = new JobRiskAssessment();
        assessment.setScore(score);
        assessment.setRiskLevel(riskLevel);
        assessment.setSummary(buildDummySummary(normalizedMode, normalizedProfession, riskLevel));
        assessment.setAssessment(buildDummyNarrative(normalizedMode, normalizedProfession, normalizedRoleSummary, score));
        return assessment;
    }

    private double scoreAdjustment(String content) {
        double adjustment = 0.0;

        if (containsAny(content, "repetitive", "data entry", "admin", "scheduling", "routine", "transcription")) {
            adjustment += 1.4;
        }
        if (containsAny(content, "analysis", "reporting", "documentation", "support", "compliance")) {
            adjustment += 0.8;
        }
        if (containsAny(content, "leadership", "negotiation", "teaching", "creative", "strategy", "hands-on", "care")) {
            adjustment -= 1.2;
        }
        if (containsAny(content, "field work", "manual", "stakeholder", "coaching", "customer relationship")) {
            adjustment -= 0.6;
        }

        return adjustment;
    }

    private boolean containsAny(String content, String... keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildDummySummary(String mode, String profession, String riskLevel) {
        String subject = "course".equals(mode) ? "career path from " + profession : profession + " role";
        return "Demo assessment: the " + subject + " shows " + riskLevel.toLowerCase(Locale.ROOT)
                + " automation pressure over the next 5-10 years.";
    }

    private String buildDummyNarrative(String mode, String profession, String roleSummary, double score) {
        String subjectLine = "course".equals(mode)
                ? "Course: " + profession
                : "Profession: " + profession;

        return subjectLine + "\n\n"
                + "This is a locally generated dummy report for development/testing. "
                + "No external AI model was called.\n\n"
                + "Estimated score: " + score + "/10\n\n"
                + "Why this score:\n"
                + "- Inputs with routine and documentation-heavy tasks trend higher risk.\n"
                + "- Inputs with creative, leadership, or people-facing work trend lower risk.\n"
                + "- Final score is deterministic for the same input so you can test repeatably.\n\n"
                + "Input excerpt:\n"
                + roleSummary;
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
