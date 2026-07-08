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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.time.Duration;

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
    private final AiCallGuard aiCallGuard;

    private final String jobAiPromptTemplate;
    private final String professionInstructions;
    private final String courseInstructions;
    private final String aLevelInstructions;

    @Autowired
    public JobAiService(@Qualifier("gpt54MiniChatClient") ChatClient gpt54MiniChatClient,
                        ResourceLoader resourceLoader,
                        GenerationMetricsService generationMetricsService,
                        JourneyConfigRegistry journeyConfigRegistry,
                        RiskScoringService riskScoringService,
                        ObjectMapper objectMapper,
                        @Value("${app.ai.model.mini}") String miniModelName,
                        @Value("${app.ai.circuit-breaker.failure-threshold:3}") int circuitBreakerFailureThreshold,
                        @Value("${app.ai.circuit-breaker.open-duration:30s}") Duration circuitBreakerOpenDuration,
                        @Value("${app.ai.circuit-breaker.max-concurrent-calls:10}") int maxConcurrentAiCalls) {
        this.gpt54MiniChatClient = gpt54MiniChatClient;
        this.resourceLoader = resourceLoader;
        this.generationMetricsService = generationMetricsService;
        this.journeyConfigRegistry = journeyConfigRegistry;
        this.riskScoringService = riskScoringService;
        this.miniModelName = miniModelName;
        this.aiCallGuard = new AiCallGuard(
                "assessment", circuitBreakerFailureThreshold, circuitBreakerOpenDuration, maxConcurrentAiCalls);
        this.objectMapper = objectMapper;

        this.jobAiPromptTemplate = loadResourceFile("classpath:prompts/jobai.txt");
        this.professionInstructions = loadPromptInstructions(JourneyType.PROFESSIONAL);
        this.courseInstructions = loadPromptInstructions(JourneyType.UNIVERSITY_STUDENT);
        this.aLevelInstructions = loadPromptInstructions(JourneyType.A_LEVEL_UNDECIDED);

        log.info("Loaded prompt templates for job risk assessment");
    }

    JobAiService(@Qualifier("gpt54MiniChatClient") ChatClient gpt54MiniChatClient,
                 ResourceLoader resourceLoader,
                 GenerationMetricsService generationMetricsService,
                 JourneyConfigRegistry journeyConfigRegistry,
                 RiskScoringService riskScoringService,
                 String miniModelName) {
        this(
                gpt54MiniChatClient,
                resourceLoader,
                generationMetricsService,
                journeyConfigRegistry,
                riskScoringService,
                new ObjectMapper(),
                miniModelName,
                3,
                Duration.ofSeconds(30),
                10
        );
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

        ChatResponse chatResponse = aiCallGuard.call(() -> gpt54MiniChatClient.prompt(prompt).call().chatResponse());
        String response = AiResponseUtils.extractContent(chatResponse);
        String cleanedResponse = AiResponseUtils.extractJson(response);

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
                    AiResponseUtils.elapsedMillis(generationStart),
                    chatResponse
            );
            assessment.setGenerationMetrics(metrics);
            generationMetricsService.logAiCost(normalizedMode, null, metrics);
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

    private String loadResourceFile(String resourcePath) {
        return AiResponseUtils.loadResource(resourceLoader, resourcePath);
    }

    private record PromptContext(String modeInstructions, String inputLabel, String detailsLabel) {
    }
}
