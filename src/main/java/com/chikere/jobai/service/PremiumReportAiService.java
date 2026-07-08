package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.GenerationMetrics;
import com.chikere.jobai.model.JourneyConfig;
import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.model.PremiumReport.*;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Calls the AI with the premium-report prompt and maps the JSON response
 * to a PremiumReport object.
 */
@Service
@Slf4j
public class PremiumReportAiService {

    private final ChatClient chatClient;
    private final ResourceLoader resourceLoader;
    private final GenerationMetricsService generationMetricsService;
    private final JourneyConfigRegistry journeyConfigRegistry;
    private final ObjectMapper objectMapper;
    private final String promptTemplate;
    private final String modelName;
    private final String reportQualityBooster;
    private final AiCallGuard aiCallGuard;

    @Autowired
    public PremiumReportAiService(@Qualifier("gpt54ReportChatClient") ChatClient gpt54ReportChatClient,
                                  GenerationMetricsService generationMetricsService,
                                  JourneyConfigRegistry journeyConfigRegistry,
                                  ResourceLoader resourceLoader,
                                  ObjectMapper objectMapper,
                                  @Value("${app.ai.model.report}") String modelName,
                                  @Value("${app.ai.report.circuit-breaker.failure-threshold:3}") int circuitBreakerFailureThreshold,
                                  @Value("${app.ai.report.circuit-breaker.open-duration:60s}") Duration circuitBreakerOpenDuration,
                                  @Value("${app.ai.report.circuit-breaker.max-concurrent-calls:4}") int maxConcurrentAiCalls) {
        this.chatClient = gpt54ReportChatClient;
        this.generationMetricsService = generationMetricsService;
        this.journeyConfigRegistry = journeyConfigRegistry;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
        this.aiCallGuard = new AiCallGuard(
                "report", circuitBreakerFailureThreshold, circuitBreakerOpenDuration, maxConcurrentAiCalls);
        this.promptTemplate = AiResponseUtils.loadResource(resourceLoader, "classpath:prompts/premium-report-prompt.txt");
        this.reportQualityBooster = AiResponseUtils.loadResource(resourceLoader, "classpath:prompts/report-quality-booster.txt");
        log.info("PremiumReportAiService initialised with model={}", modelName);
    }

    PremiumReportAiService(ChatClient chatClient,
                           GenerationMetricsService generationMetricsService,
                           JourneyConfigRegistry journeyConfigRegistry,
                           ResourceLoader resourceLoader,
                           String modelName) {
        this(chatClient, generationMetricsService, journeyConfigRegistry, resourceLoader,
                new ObjectMapper(), modelName, 3, Duration.ofSeconds(60), 4);
    }

    /**
     * Generates a fully AI-populated PremiumReport for the given request.
     */
    public PremiumReport generate(GenerateReportRequest request) {
        return generate(request, UUID.randomUUID().toString());
    }

    /**
     * Variant used by the async flow, where the report row (and its id) is created
     * before generation starts so the client can poll for status.
     */
    public PremiumReport generate(GenerateReportRequest request, String reportId) {
        long generationStart = System.nanoTime();
        log.info("Generating premium report id={}", reportId);

        JourneyType journeyType = JourneyType.fromMode(request.getMode());
        JourneyConfig config = journeyConfigRegistry.get(journeyType);
        String profession = normalise(request.getProfession(), "Unknown");
        String prompt = buildPremiumPrompt(request, journeyType, config);

        log.info("Calling AI: model={} reportId={}", modelName, reportId);

        ChatResponse chatResponse = aiCallGuard.call(() -> chatClient.prompt(prompt).call().chatResponse());
        String raw = AiResponseUtils.extractContent(chatResponse);
        String json = AiResponseUtils.extractJson(raw);

        try {
            JsonNode root = objectMapper.readTree(json);
            PremiumReport report = mapToReport(reportId, request, root);
            GenerationMetrics metrics = generationMetricsService.fromChatResponse(
                    "Full Report",
                    modelName,
                    AiResponseUtils.elapsedMillis(generationStart),
                    chatResponse
            );
            report.setGenerationMetrics(metrics);
            generationMetricsService.logAiCost(journeyType.legacyMode(), reportId, metrics);
            return report;
        } catch (Exception e) {
            log.error("Failed to parse AI response for reportId={}", reportId, e);
            throw new RuntimeException("Failed to parse premium report AI response", e);
        }
    }

    String buildPremiumPrompt(GenerateReportRequest request) {
        JourneyType journeyType = JourneyType.fromMode(request.getMode());
        return buildPremiumPrompt(request, journeyType, journeyConfigRegistry.get(journeyType));
    }

    private String buildPremiumPrompt(GenerateReportRequest request,
                                      JourneyType journeyType,
                                      JourneyConfig config) {
        String mode = config.legacyModeValue();
        String profession = cap(normalise(request.getProfession(), "Unknown"), 300);
        String description = cap(normalise(request.getDescription(), "Not provided"), 4000);

        return promptTemplate
                .replace("{mode}", mode)
                .replace("{journeyType}", journeyType.name())
                .replace("{journeyDisplayName}", config.displayName())
                .replace("{inputLabel}", config.subjectLabel())
                .replace("{detailsLabel}", config.detailsLabel())
                .replace("{reportFraming}", reportFraming(journeyType))
                .replace("{sectionEmphasis}", sectionEmphasis(journeyType))
                .replace("{journeyInstructions}", journeyInstructions(journeyType))
                .replace("{reportQualityBooster}", reportQualityBooster)
                .replace("{profession}", profession)
                .replace("{roleSummary}", description)
                .replace("{score}", String.format("%.1f", request.getScore()))
                .replace("{riskLevel}", normalise(request.getRiskLevel(), "Moderate"));
    }

    // ─── Mapping ─────────────────────────────────────────────────────────────

    PremiumReport mapToReport(String reportId, GenerateReportRequest req, JsonNode root) {
        return PremiumReport.builder()
                .reportId(reportId)
                .profession(req.getProfession())
                .mode(req.getMode())
                .score(AiResponseUtils.clampScore(req.getScore()))
                .riskLevel(normalise(req.getRiskLevel(), deriveRiskLevel(AiResponseUtils.clampScore(req.getScore()))))
                .scoreRationale(text(root, "scoreRationale"))
                .generatedAt(LocalDateTime.now())

                // KPIs
                .disruptionWindow(text(root, "disruptionWindow"))
                .adaptabilityPotential(normaliseToWordLimit(text(root, "adaptabilityPotential"), 2))
                .overallExposure(text(root, "overallExposure"))
                .mostExposedArea(text(root, "mostExposedArea"))
                .safestDirection(text(root, "safestDirection"))
                .coreAdvice(text(root, "coreAdvice"))

                // Narratives
                .executiveSummary(text(root, "executiveSummary"))
                .positioningAdvice(text(root, "positioningAdvice"))

                // Tables
                .taskExposureMap(toTaskRows(root.path("taskExposureMap")))
                .timelineEvents(toTimelineEvents(root.path("timelineEvents")))
                .skillCards(toSkillCards(root.path("skillCards")))
                .careerLevelAnalysis(toCareerLevelRows(root.path("careerLevelAnalysis")))
                .trackComparison(toTrackRows(root.path("trackComparison")))
                .adjacentRoles(toTransitionRows(root.path("adjacentRoles")))
                .resilienceScorecard(toResilienceRows(root.path("resilienceScorecard")))
                .resources(toResources(root.path("resources")))

                // Salary section
                .salaryTraditionalTitle(text(root, "salaryTraditionalTitle"))
                .salaryTraditionalMedian(text(root, "salaryTraditionalMedian"))
                .salaryTraditionalRange(text(root, "salaryTraditionalRange"))
                .salaryTraditionalBullets(toStringList(root.path("salaryTraditionalBullets")))
                .salaryAiTitle(text(root, "salaryAiTitle"))
                .salaryAiMedian(text(root, "salaryAiMedian"))
                .salaryAiRange(text(root, "salaryAiRange"))
                .salaryAiBullets(toStringList(root.path("salaryAiBullets")))
                .consultancyOpportunity(text(root, "consultancyOpportunity"))

                // Lists
                .aiStrengths(toStringList(root.path("aiStrengths")))
                .humanAdvantages(toStringList(root.path("humanAdvantages")))
                .warningSigns(toStringList(root.path("warningSigns")))

                // Action plan
                .thirtyDayPlan(toStringList(root.path("thirtyDayPlan")))
                .ninetyDayPlan(toStringList(root.path("ninetyDayPlan")))
                .yearPlan(toStringList(root.path("yearPlan")))

                .build();
    }

    private List<TaskRow> toTaskRows(JsonNode node) {
        List<TaskRow> rows = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                rows.add(new TaskRow(
                        text(n, "area"),
                        intValue(n, "exposurePercent"),
                        text(n, "exposure"),
                        text(n, "timelineLabel"),
                        text(n, "reason")
                ));
            }
        }
        return rows;
    }

    private List<TimelineEvent> toTimelineEvents(JsonNode node) {
        List<TimelineEvent> events = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                events.add(new TimelineEvent(
                        text(n, "period"),
                        text(n, "title"),
                        text(n, "description"),
                        toStringList(n.path("tags"))
                ));
            }
        }
        return events;
    }

    private List<SkillCard> toSkillCards(JsonNode node) {
        List<SkillCard> cards = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                cards.add(new SkillCard(
                        text(n, "icon"),
                        text(n, "name"),
                        text(n, "description"),
                        text(n, "priority"),
                        text(n, "priorityNote")
                ));
            }
        }
        return cards;
    }

    private List<CareerLevelRow> toCareerLevelRows(JsonNode node) {
        List<CareerLevelRow> rows = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                rows.add(new CareerLevelRow(
                        text(n, "level"),
                        text(n, "risk"),
                        text(n, "commentary")
                ));
            }
        }
        return rows;
    }

    private List<TrackRow> toTrackRows(JsonNode node) {
        List<TrackRow> rows = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                rows.add(new TrackRow(
                        text(n, "track"),
                        text(n, "exposure"),
                        text(n, "reason")
                ));
            }
        }
        return rows;
    }

    private List<TransitionRow> toTransitionRows(JsonNode node) {
        List<TransitionRow> rows = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                rows.add(new TransitionRow(
                        text(n, "targetRole"),
                        text(n, "salaryBand"),
                        text(n, "aiResilience"),
                        text(n, "transitionDifficulty"),
                        text(n, "reason"),
                        toStringList(n.path("skillTags"))
                ));
            }
        }
        return rows;
    }

    private List<ResourceCard> toResources(JsonNode node) {
        List<ResourceCard> resources = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                resources.add(new ResourceCard(
                        text(n, "type"),
                        text(n, "title"),
                        text(n, "description")
                ));
            }
        }
        return resources;
    }

    private String reportFraming(JourneyType journeyType) {
        return switch (journeyType) {
            case UNIVERSITY_STUDENT ->
                    "Frame this as a degree and early-career strategy report. Analyse the course, likely graduate routes, specialisations, and skills to build during study.";
            case A_LEVEL_UNDECIDED ->
                    "Frame this as an AI-ready study and career planning report for a GCSE/Year 11/A-Level student who may not know their future career yet.";
            case PROFESSIONAL ->
                    "Frame this as a career survival plan for the user's current profession, focused on task-level automation and practical repositioning.";
        };
    }

    private String sectionEmphasis(JourneyType journeyType) {
        return switch (journeyType) {
            case UNIVERSITY_STUDENT ->
                    "Use course relevance, likely career paths, AI exposure of those paths, specialisations to consider, and skills to build during the course.";
            case A_LEVEL_UNDECIDED ->
                    "Use suggested subject/A-Level combinations, career clusters, AI exposure of possible paths, skills to build while still in school, and next steps for choosing subjects.";
            case PROFESSIONAL ->
                    "Use task-level automation, skills that stay valuable, adjacent roles, salary/market shifts, and a career survival plan.";
        };
    }

    private String journeyInstructions(JourneyType journeyType) {
        return switch (journeyType) {
            case UNIVERSITY_STUDENT -> """
                    UNIVERSITY_STUDENT JOURNEY:
                    - Treat the subject as a course or degree, not a current job.
                    - Focus on the careers this course may lead to and how AI may affect those paths.
                    - Recommend specialisations, placements, projects, modules, portfolios, and tools that improve employability.
                    - Map taskExposureMap to likely graduate work areas or future career-path exposures.
                    - Map adjacentRoles to related career routes or specialisations the student could pursue.
                    - Keep salary guidance graduate-career oriented. Express all salary figures in USD ($) as a global reference point.
                    """;
            case A_LEVEL_UNDECIDED -> """
                    A_LEVEL_UNDECIDED JOURNEY:
                    - Treat the subject as possible GCSE/A-Level subjects, interests, strengths, and preferences, not a profession.
                    - The user may not know their degree or career yet; avoid pretending certainty.
                    - Suggest sensible subject/A-Level combinations and explain what routes they keep open.
                    - Map taskExposureMap to possible future path exposures or study/career clusters.
                    - Map skillCards to school-stage and early-career skills to build.
                    - Map adjacentRoles to possible future career clusters, not fixed job transitions.
                    - Map action plans to next study and career decision steps.
                    - Map resources to student-friendly learning, exploration, and subject-choice resources.
                    - If salary fields are required, use broad future-path market context rather than implying the student is already in a job.
                    """;
            case PROFESSIONAL -> """
                    PROFESSIONAL JOURNEY:
                    - Treat the subject as the user's current profession or role.
                    - Focus on day-to-day task automation, what changes first, skills that stay valuable, adjacent roles, and salary/market shifts.
                    - Map taskExposureMap to concrete tasks from the role.
                    - Map adjacentRoles to realistic transitions from this profession.
                    - Keep recommendations practical for a working professional.
                    """;
        };
    }

    private List<ResilienceRow> toResilienceRows(JsonNode node) {
        List<ResilienceRow> rows = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                rows.add(new ResilienceRow(
                        text(n, "factor"),
                        text(n, "signal")
                ));
            }
        }
        return rows;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode n : node) {
                list.add(n.asText());
            }
        }
        return list;
    }

    // ─── Utilities ────────────────────────────────────────────────────────────

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    private String normaliseToWordLimit(String value, int maxWords) {
        String cleaned = normalise(value, "").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty() || maxWords <= 0) {
            return "";
        }

        String[] words = cleaned.split(" ");
        if (words.length <= maxWords) {
            return cleaned;
        }

        StringBuilder limited = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) {
                limited.append(' ');
            }
            limited.append(words[i]);
        }
        return limited.toString();
    }

    private int intValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isInt() || value.isLong() || value.isFloat() || value.isDouble()) {
            return clampPercent(value.asInt());
        }

        String raw = value.asText("");
        if (raw.isBlank()) {
            return 0;
        }

        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return 0;
        }

        try {
            return clampPercent(Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String deriveRiskLevel(double score) {
        if (score <= 3.4) {
            return "Low";
        }
        if (score <= 6.9) {
            return "Moderate";
        }
        return "High";
    }

    private String normalise(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private String cap(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
