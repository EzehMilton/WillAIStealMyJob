package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerationMetrics;
import com.chikere.jobai.model.JobRiskAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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

    private static final String MODE_COURSE = "course";
    private static final String MODE_PROFESSION = "profession";

    private final ChatClient gpt54ChatClient;
    private final ChatClient gpt54MiniChatClient;
    private final ResourceLoader resourceLoader;
    private final GenerationMetricsService generationMetricsService;
    private final boolean useDummyMode;
    private final ObjectMapper objectMapper;
    private final String premiumModelName;
    private final String miniModelName;

    private final String jobAiPromptTemplate;
    private final String professionInstructions;
    private final String courseInstructions;

    public JobAiService(@Qualifier("gpt54ChatClient") ChatClient gpt54ChatClient,
                        @Qualifier("gpt54MiniChatClient") ChatClient gpt54MiniChatClient,
                        ResourceLoader resourceLoader,
                        GenerationMetricsService generationMetricsService,
                        @Value("${app.ai.use-dummy:false}") boolean useDummyMode,
                        @Value("${app.ai.model.premium}") String premiumModelName,
                        @Value("${app.ai.model.mini}") String miniModelName) {
        this.gpt54ChatClient = gpt54ChatClient;
        this.gpt54MiniChatClient = gpt54MiniChatClient;
        this.resourceLoader = resourceLoader;
        this.generationMetricsService = generationMetricsService;
        this.useDummyMode = useDummyMode;
        this.premiumModelName = premiumModelName;
        this.miniModelName = miniModelName;
        this.objectMapper = new ObjectMapper();

        this.jobAiPromptTemplate = loadResourceFile("classpath:prompts/jobai.txt");
        this.professionInstructions = loadResourceFile("classpath:prompts/profession-instructions.txt");
        this.courseInstructions = loadResourceFile("classpath:prompts/course-instructions.txt");

        log.info("Loaded prompt templates for job risk assessment");

        if (this.useDummyMode) {
            log.info("JobAiService is running in dummy mode. No external AI model calls will be made.");
        }
    }

    public JobRiskAssessment assessJobRisk(String mode, String profession, String roleSummary) {
        long generationStart = System.nanoTime();
        String normalizedMode = normalizeMode(mode);
        String normalizedProfession = normalizeProfession(profession);
        String normalizedRoleSummary = normalizeRoleSummary(roleSummary);

        log.info("Assessing job risk - Summary Report - for mode: {}, profession: {}", normalizedMode, normalizedProfession);

        if (useDummyMode) {
            JobRiskAssessment assessment = buildDummyAssessment(normalizedMode, normalizedProfession, normalizedRoleSummary);
            GenerationMetrics metrics = generationMetricsService.forLocalGeneration("Summary Report", elapsedMillis(generationStart));
            assessment.setGenerationMetrics(metrics);
            logGenerationSummary(normalizedProfession, metrics);
            return assessment;
        }

        PromptContext promptContext = buildPromptContext(normalizedMode);

        String prompt = jobAiPromptTemplate
                .replace("{mode}", normalizedMode)
                .replace("{modeInstructions}", promptContext.modeInstructions())
                .replace("{inputLabel}", promptContext.inputLabel())
                .replace("{detailsLabel}", promptContext.detailsLabel())
                .replace("{profession}", normalizedProfession)
                .replace("{roleSummary}", normalizedRoleSummary);

        log.debug("Generated prompt for assessment");

        ChatClient selectedChatClient = selectAssessmentModel(normalizedMode);
        String modelName = MODE_COURSE.equals(normalizedMode) ? miniModelName : premiumModelName;
        log.info("Calling AI: model={} profession=\"{}\"", modelName, normalizedProfession);

        ChatResponse chatResponse = selectedChatClient.prompt(prompt).call().chatResponse();
        String response = extractContent(chatResponse);
        String cleanedResponse = cleanJsonResponse(response);

        log.debug("Received assessment response: {}", cleanedResponse);

        try {
            JobRiskAssessment assessment = objectMapper.readValue(cleanedResponse, JobRiskAssessment.class);
            GenerationMetrics metrics = generationMetricsService.fromChatResponse(
                    "Summary Report",
                    modelName,
                    elapsedMillis(generationStart),
                    chatResponse
            );
            assessment.setGenerationMetrics(metrics);
            logGenerationSummary(normalizedProfession, metrics);
            return assessment;
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", response, e);
            throw new RuntimeException("Failed to parse AI response", e);
        }
    }

    private ChatClient selectAssessmentModel(String mode) {
        return MODE_COURSE.equals(mode) ? gpt54MiniChatClient : gpt54ChatClient;
    }

    private PromptContext buildPromptContext(String mode) {
        if (MODE_COURSE.equals(mode)) {
            return new PromptContext(
                    courseInstructions,
                    "Course/Degree",
                    "Expected Career Path"
            );
        }

        return new PromptContext(
                professionInstructions,
                "Profession",
                "Role Details"
        );
    }

    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return MODE_PROFESSION;
        }

        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return MODE_COURSE.equals(normalized) ? MODE_COURSE : MODE_PROFESSION;
    }

    private String normalizeProfession(String profession) {
        return profession == null || profession.isBlank() ? "Unknown" : profession.trim();
    }

    private String normalizeRoleSummary(String roleSummary) {
        return roleSummary == null ? "" : roleSummary.trim();
    }

    private JobRiskAssessment buildDummyAssessment(String mode, String profession, String roleSummary) {
        String content = roleSummary.toLowerCase(Locale.ROOT);

        double score = (Math.floorMod(
                Objects.hash(mode, profession.toLowerCase(Locale.ROOT), content), 71
        ) / 10.0) + 1.5;

        score += scoreAdjustment(content);
        score = Math.max(0.5, Math.min(9.5, Math.round(score * 10.0) / 10.0));

        String riskLevel = score <= 2 ? "Low" : (score <= 6 ? "Moderate" : "High");

        JobRiskAssessment assessment = new JobRiskAssessment();
        assessment.setScore(score);
        assessment.setRiskLevel(riskLevel);
        assessment.setSummary(buildDummySummary(mode, profession, riskLevel));
        assessment.setAssessment(buildDummyNarrative(mode, profession, roleSummary, score));

        return assessment;
    }

    private String extractContent(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null || chatResponse.getResult().getOutput() == null) {
            return "";
        }
        return chatResponse.getResult().getOutput().getText();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void logGenerationSummary(String profession, GenerationMetrics metrics) {
        log.info(
                "{} completed for profession=\"{}\": model={} durationMs={} promptTokens={} completionTokens={} totalTokens={} estimatedCostUsd={}",
                metrics.getReportType(),
                profession,
                metrics.getModel(),
                metrics.getDurationMs(),
                metrics.getPromptTokens(),
                metrics.getCompletionTokens(),
                metrics.getTotalTokens(),
                metrics.getEstimatedCostUsdLabel()
        );
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
        String subject = MODE_COURSE.equals(mode)
                ? "career path from " + profession
                : profession + " role";

        return "Demo assessment: the " + subject + " shows "
                + riskLevel.toLowerCase(Locale.ROOT)
                + " automation pressure over the next 5-10 years.";
    }

    private String buildDummyNarrative(String mode, String profession, String roleSummary, double score) {
        String subjectLine = MODE_COURSE.equals(mode)
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

    private record PromptContext(String modeInstructions, String inputLabel, String detailsLabel) {
    }
}
