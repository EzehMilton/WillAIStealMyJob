package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerationMetrics;
import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.JourneyConfig;
import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskScoringResult;
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

@Service
@Slf4j
public class JobAiService {

    private final ChatClient gpt54MiniChatClient;
    private final ResourceLoader resourceLoader;
    private final GenerationMetricsService generationMetricsService;
    private final ObjectMapper objectMapper;
    private final String miniModelName;
    private final JourneyConfigRegistry journeyConfigRegistry;
    private final RiskScoringService riskScoringService;

    private final String jobAiPromptTemplate;
    private final String professionInstructions;
    private final String courseInstructions;
    private final String aLevelInstructions;

    public JobAiService(@Qualifier("gpt54MiniChatClient") ChatClient gpt54MiniChatClient,
                        ResourceLoader resourceLoader,
                        GenerationMetricsService generationMetricsService,
                        JourneyConfigRegistry journeyConfigRegistry,
                        RiskScoringService riskScoringService,
                        @Value("${app.ai.model.mini}") String miniModelName) {
        this.gpt54MiniChatClient = gpt54MiniChatClient;
        this.resourceLoader = resourceLoader;
        this.generationMetricsService = generationMetricsService;
        this.journeyConfigRegistry = journeyConfigRegistry;
        this.riskScoringService = riskScoringService;
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

        log.info("Assessing job risk - Summary Report - for mode: {}", normalizedMode);

        String prompt = buildAssessmentPrompt(normalizedMode, normalizedProfession, normalizedRoleSummary);

        log.debug("Generated prompt for assessment");

        log.info("Calling AI: model={}", miniModelName);

        ChatResponse chatResponse = gpt54MiniChatClient.prompt(prompt).call().chatResponse();
        String response = extractContent(chatResponse);
        String cleanedResponse = cleanJsonResponse(response);

        log.debug("Received assessment response: {}", cleanedResponse);

        try {
            JobRiskAssessment assessment = objectMapper.readValue(cleanedResponse, JobRiskAssessment.class);
            RiskScoringResult scoringResult = riskScoringService.score(
                    journeyType,
                    normalizedProfession,
                    normalizedRoleSummary,
                    assessment.getSummary()
            );
            assessment.setScore(scoringResult.score());
            assessment.setRiskLevel(scoringResult.riskLevel());
            assessment.setSummary(scoringResult.summary());
            GenerationMetrics metrics = generationMetricsService.fromChatResponse(
                    "Summary Report",
                    miniModelName,
                    elapsedMillis(generationStart),
                    chatResponse
            );
            assessment.setGenerationMetrics(metrics);
            logGenerationSummary(normalizedMode, metrics);
            return assessment;
        } catch (Exception e) {
            log.error("Failed to parse AI response: {}", response, e);
            throw new RuntimeException("Failed to parse AI response", e);
        }
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

    private void logGenerationSummary(String mode, GenerationMetrics metrics) {
        log.info(
                "AI_COST reportType=\"{}\" mode={} model={} durationMs={} promptTokens={} completionTokens={} totalTokens={} inputCostUsd={} outputCostUsd={} estimatedCostUsd={} estimatedCostPence={}",
                metrics.getReportType(),
                mode,
                metrics.getModel(),
                metrics.getDurationMs(),
                metrics.getPromptTokens(),
                metrics.getCompletionTokens(),
                metrics.getTotalTokens(),
                metrics.getInputCostUsdLabel(),
                metrics.getOutputCostUsdLabel(),
                metrics.getEstimatedCostUsdLabel(),
                metrics.getEstimatedCostPenceLabel()
        );
    }

    double clampScore(double score) {
        if (Double.isNaN(score) || Double.isInfinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(10.0, score));
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
