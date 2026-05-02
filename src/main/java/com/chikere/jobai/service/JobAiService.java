package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerationMetrics;
import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.JourneyConfig;
import com.chikere.jobai.model.JourneyType;
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

@Service
@Slf4j
public class JobAiService {

    private final ChatClient gpt54ChatClient;
    private final ChatClient gpt54MiniChatClient;
    private final ResourceLoader resourceLoader;
    private final GenerationMetricsService generationMetricsService;
    private final ObjectMapper objectMapper;
    private final String premiumModelName;
    private final String miniModelName;
    private final JourneyConfigRegistry journeyConfigRegistry;

    private final String jobAiPromptTemplate;
    private final String professionInstructions;
    private final String courseInstructions;
    private final String aLevelInstructions;

    public JobAiService(@Qualifier("gpt54ChatClient") ChatClient gpt54ChatClient,
                        @Qualifier("gpt54MiniChatClient") ChatClient gpt54MiniChatClient,
                        ResourceLoader resourceLoader,
                        GenerationMetricsService generationMetricsService,
                        JourneyConfigRegistry journeyConfigRegistry,
                        @Value("${app.ai.model.premium}") String premiumModelName,
                        @Value("${app.ai.model.mini}") String miniModelName) {
        this.gpt54ChatClient = gpt54ChatClient;
        this.gpt54MiniChatClient = gpt54MiniChatClient;
        this.resourceLoader = resourceLoader;
        this.generationMetricsService = generationMetricsService;
        this.journeyConfigRegistry = journeyConfigRegistry;
        this.premiumModelName = premiumModelName;
        this.miniModelName = miniModelName;
        this.objectMapper = new ObjectMapper();

        this.jobAiPromptTemplate = loadResourceFile("classpath:prompts/jobai.txt");
        this.professionInstructions = loadPromptInstructions(JourneyType.PROFESSIONAL);
        this.courseInstructions = loadPromptInstructions(JourneyType.UNIVERSITY_STUDENT);
        this.aLevelInstructions = loadPromptInstructions(JourneyType.A_LEVEL_UNDECIDED);

        log.info("Loaded prompt templates for job risk assessment");
    }

    public JobRiskAssessment assessJobRisk(String mode, String profession, String roleSummary) {
        long generationStart = System.nanoTime();
        JourneyType journeyType = JourneyType.fromMode(mode);
        String normalizedMode = journeyType.legacyMode();
        String normalizedProfession = normalizeProfession(profession);
        String normalizedRoleSummary = normalizeRoleSummary(roleSummary);

        log.info("Assessing job risk - Summary Report - for mode: {}, profession: {}", normalizedMode, normalizedProfession);

        String prompt = buildAssessmentPrompt(normalizedMode, normalizedProfession, normalizedRoleSummary);

        log.debug("Generated prompt for assessment");

        ChatClient selectedChatClient = selectAssessmentModel(normalizedMode);
        String modelName = journeyType.isProfessional() ? premiumModelName : miniModelName;
        log.info("Calling AI: model={} profession=\"{}\"", modelName, normalizedProfession);

        ChatResponse chatResponse = selectedChatClient.prompt(prompt).call().chatResponse();
        String response = extractContent(chatResponse);
        String cleanedResponse = cleanJsonResponse(response);

        log.debug("Received assessment response: {}", cleanedResponse);

        try {
            JobRiskAssessment assessment = objectMapper.readValue(cleanedResponse, JobRiskAssessment.class);
            double calibratedScore = calibrateScore(
                    journeyType,
                    normalizedProfession,
                    normalizedRoleSummary,
                    assessment.getScore()
            );
            assessment.setScore(calibratedScore);
            assessment.setRiskLevel(deriveRiskLevel(calibratedScore));
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
        return journeyConfigRegistry.get(mode).journeyType().isProfessional() ? gpt54ChatClient : gpt54MiniChatClient;
    }

    String buildAssessmentPrompt(String mode, String profession, String roleSummary) {
        PromptContext promptContext = buildPromptContext(mode);
        return jobAiPromptTemplate
                .replace("{mode}", mode)
                .replace("{modeInstructions}", promptContext.modeInstructions())
                .replace("{inputLabel}", promptContext.inputLabel())
                .replace("{detailsLabel}", promptContext.detailsLabel())
                .replace("{profession}", profession)
                .replace("{roleSummary}", roleSummary);
    }

    private PromptContext buildPromptContext(String mode) {
        JourneyConfig config = journeyConfigRegistry.get(mode);

        if (config.journeyType().isUniversityStudent()) {
            return new PromptContext(
                    courseInstructions,
                    config.subjectLabel(),
                    config.detailsLabel()
            );
        }

        if (config.journeyType() == JourneyType.A_LEVEL_UNDECIDED) {
            return new PromptContext(
                    aLevelInstructions,
                    config.subjectLabel(),
                    config.detailsLabel()
            );
        }

        return new PromptContext(
                professionInstructions,
                config.subjectLabel(),
                config.detailsLabel()
        );
    }

    private String loadPromptInstructions(JourneyType journeyType) {
        return loadResourceFile("classpath:prompts/" + journeyConfigRegistry.get(journeyType).promptInstructionResource());
    }

    private String normalizeProfession(String profession) {
        return profession == null || profession.isBlank() ? "Unknown" : profession.trim();
    }

    private String normalizeRoleSummary(String roleSummary) {
        return roleSummary == null ? "" : roleSummary.trim();
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

    double calibrateScore(JourneyType journeyType, String profession, String roleSummary, double modelScore) {
        double heuristicScore = heuristicScore(journeyType, profession, roleSummary);
        double clampedModelScore = clampScore(modelScore);
        double calibrated = (clampedModelScore * 0.65) + (heuristicScore * 0.35);
        return roundScore(calibrated);
    }

    private double heuristicScore(JourneyType journeyType, String profession, String roleSummary) {
        String text = (profession + " " + roleSummary).toLowerCase(Locale.ROOT);

        double score = switch (journeyType) {
            case PROFESSIONAL -> 4.7;
            case UNIVERSITY_STUDENT -> 4.1;
            case A_LEVEL_UNDECIDED -> 4.0;
        };

        score += keywordAdjustment(text,
                1.2,
                "data entry", "admin", "routine", "repetitive", "scheduling", "transcription",
                "reporting", "documentation", "processing", "forms", "claims", "invoice"
        );
        score += keywordAdjustment(text,
                0.9,
                "developer", "software", "java", "code", "coding", "testing", "debugging",
                "analysis", "analyst", "spreadsheet", "compliance", "customer support"
        );
        score += keywordAdjustment(text,
                -1.2,
                "social care", "care", "safeguarding", "empathy", "hands-on", "patient",
                "teaching", "counselling", "therapy", "nursing", "field work"
        );
        score += keywordAdjustment(text,
                -0.8,
                "leadership", "negotiation", "strategy", "stakeholder", "creative",
                "manual", "relationship", "coaching", "human judgement"
        );

        if (journeyType == JourneyType.UNIVERSITY_STUDENT && containsAny(text, "social care", "health", "nursing", "teaching")) {
            score -= 0.6;
        }

        if (journeyType == JourneyType.PROFESSIONAL && containsAny(text, "developer", "software", "java")) {
            score += 0.4;
        }

        return roundScore(score);
    }

    private double keywordAdjustment(String text, double adjustment, String... keywords) {
        int matches = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                matches++;
            }
        }
        if (matches == 0) {
            return 0.0;
        }
        return adjustment * Math.min(2, matches);
    }

    private double roundScore(double score) {
        return Math.max(0.5, Math.min(9.5, Math.round(score * 10.0) / 10.0));
    }

    double clampScore(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(10.0, score));
    }

    String deriveRiskLevel(double score) {
        if (score < 3.0) {
            return "Low";
        }
        if (score < 7.0) {
            return "Moderate";
        }
        return "High";
    }

    private boolean containsAny(String content, String... keywords) {
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
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
