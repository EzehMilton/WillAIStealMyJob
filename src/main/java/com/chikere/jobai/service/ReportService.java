package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.model.PremiumReport.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReportService {

    private final Map<String, PremiumReport> store = new ConcurrentHashMap<>();

    public PremiumReport generateReport(GenerateReportRequest request) {
        String reportId = UUID.randomUUID().toString();
        PremiumReport report = buildMockReport(reportId, request);
        store.put(reportId, report);
        return report;
    }

    public Optional<PremiumReport> getReport(String reportId) {
        return Optional.ofNullable(store.get(reportId));
    }

    // -------------------------------------------------------------------------
    // Mock builder — replace method bodies with Spring AI calls in future
    // -------------------------------------------------------------------------

    private PremiumReport buildMockReport(String reportId, GenerateReportRequest req) {
        String p = normalise(req.getProfession());
        String rl = normalise(req.getRiskLevel());
        boolean high = "High".equalsIgnoreCase(rl);
        boolean low  = "Low".equalsIgnoreCase(rl);

        return PremiumReport.builder()
                .reportId(reportId)
                .profession(p)
                .mode(req.getMode())
                .score(req.getScore())
                .riskLevel(rl.isEmpty() ? "Moderate" : rl)
                .generatedAt(LocalDateTime.now())
                .overallExposure(rl.isEmpty() ? "Moderate" : rl)
                .mostExposedArea(high ? "Routine and repetitive tasks" : "Data processing and reporting")
                .safestDirection(low ? "Strategic and leadership roles" : "Specialisation and leadership")
                .coreAdvice(high ? "Upskill urgently and reposition" : low ? "Stay current and deepen expertise" : "Move up the value chain")
                .executiveSummary(executiveSummary(p, rl, req.getScore()))
                .taskExposureMap(taskRows(rl))
                .aiStrengths(aiStrengths())
                .humanAdvantages(humanAdvantages())
                .careerLevelAnalysis(careerLevels(p))
                .trackComparison(tracks(p))
                .adjacentRoles(adjacentRoles(p))
                .thirtyDayPlan(thirtyDayPlan(p))
                .ninetyDayPlan(ninetyDayPlan(p))
                .yearPlan(yearPlan(p))
                .warningSigns(warningSigns())
                .resilienceScorecard(resilienceScorecard())
                .positioningAdvice(positioningAdvice(p, rl))
                .build();
    }

    private String executiveSummary(String profession, String riskLevel, double score) {
        String opening = switch (riskLevel.toLowerCase()) {
            case "high" -> profession + " faces significant AI exposure. A substantial portion of day-to-day work involves predictable, rule-based tasks that AI systems are increasingly capable of handling at scale.";
            case "low"  -> profession + " carries relatively low AI exposure. The core activities of this role rely heavily on human judgement, contextual reasoning, and interpersonal skills that remain difficult to automate.";
            default     -> profession + " faces moderate AI exposure. Some routine tasks are under automation pressure, while higher-value work involving judgement, creativity, and stakeholder relationships remains resilient.";
        };

        String advice = switch (riskLevel.toLowerCase()) {
            case "high" -> " Professionals in this field should act now: move toward higher-complexity work, develop specialist depth, and treat AI tools as productivity amplifiers rather than threats.";
            case "low"  -> " The priority is to stay current with AI tools that enhance output, build on specialist and strategic depth, and maintain the human-centric skills that define this role's long-term value.";
            default     -> " The opportunity lies in repositioning away from routine delivery and toward ownership, strategy, and the kind of judgment-rich work that AI struggles to replicate consistently.";
        };

        return opening
                + " The AI risk score for this role is " + String.format("%.1f", score) + " out of 10."
                + advice;
    }

    private List<TaskRow> taskRows(String riskLevel) {
        boolean high = "High".equalsIgnoreCase(riskLevel);
        boolean low  = "Low".equalsIgnoreCase(riskLevel);
        return List.of(
                new TaskRow("Routine and repetitive tasks",       high ? "High"          : low ? "Low"          : "Moderate–High",  "Predictable, rule-based workflows are the first area AI tools compress at scale."),
                new TaskRow("Data processing and reporting",      high ? "High"          : low ? "Low–Moderate" : "Moderate–High",  "AI excels at aggregating structured data and drafting standard reports."),
                new TaskRow("Research and information gathering", high ? "Moderate–High" : low ? "Low"          : "Moderate",       "AI can surface and summarise information quickly, reducing manual search time."),
                new TaskRow("Analysis and problem-solving",       high ? "Moderate"      : low ? "Low"          : "Moderate",       "Pattern recognition assists analysis, but complex judgement still requires human experience."),
                new TaskRow("Communication and stakeholder work", high ? "Low–Moderate"  : low ? "Very Low"     : "Low–Moderate",   "Relationship management, negotiation, and nuanced communication remain human-led."),
                new TaskRow("Strategic planning and decisions",   high ? "Low"           : low ? "Very Low"     : "Low",            "High-stakes decisions with incomplete information continue to rely on human experience."),
                new TaskRow("Leadership and team management",     "Very Low",                                                        "People leadership, coaching, and organisational influence are not automation targets.")
        );
    }

    private List<String> aiStrengths() {
        return List.of(
                "Generate first drafts of standard documents, summaries, and structured outputs.",
                "Process and cross-reference large volumes of data faster than any human team.",
                "Identify patterns in historical data to surface predictions and anomalies.",
                "Automate repetitive multi-step workflows with high consistency and zero fatigue.",
                "Translate between formats, languages, and information structures at speed."
        );
    }

    private List<String> humanAdvantages() {
        return List.of(
                "Owning the consequences of decisions over the long term.",
                "Understanding hidden organisational context, undocumented assumptions, and political dynamics.",
                "Balancing competing priorities across stakeholders with differing goals.",
                "Making judgement calls when information is ambiguous, incomplete, or emotionally charged.",
                "Building trust, influence, and professional relationships that compound over a career."
        );
    }

    private List<CareerLevelRow> careerLevels(String profession) {
        return List.of(
                new CareerLevelRow("Early-career / Junior",       "Moderate–High", "Most exposed when work is task-based, closely specified, and repetitive."),
                new CareerLevelRow("Mid-level",                   "Moderate",      "Safer when moving beyond delivery into ownership, analysis, and independent judgement."),
                new CareerLevelRow("Senior",                      "Low–Moderate",  "Resilient when responsible for strategy, trade-offs, stakeholder alignment, and quality standards."),
                new CareerLevelRow("Lead / Principal / Specialist","Low",           "Work is rooted in deep expertise, influence, and long-term accountability."),
                new CareerLevelRow("Manager / Director",          "Low",           "Leadership, prioritisation, people development, and delivery coordination remain human-dominant.")
        );
    }

    private List<TrackRow> tracks(String profession) {
        return List.of(
                new TrackRow("High-volume transactional work",    "High",          "Large portions of routine throughput can be compressed or partially automated."),
                new TrackRow("Reporting and compliance",          "Moderate–High", "AI can generate standard reports and flag compliance issues from structured data."),
                new TrackRow("Client-facing advisory roles",      "Moderate",      "Still valuable, but commodity advice is increasingly assisted by AI tools."),
                new TrackRow("Operations and process management", "Moderate",      "Routine coordination is pressured; complex operational judgement remains valuable."),
                new TrackRow("Specialist / expert tracks",        "Low",           "Deep domain expertise and independent judgement are resilient to automation."),
                new TrackRow("Strategic and leadership tracks",   "Low",           "Direction-setting, resource allocation, and people leadership are not automation targets."),
                new TrackRow("AI-adjacent roles",                 "Low",           "Roles that leverage AI as a core tool are likely to grow in demand.")
        );
    }

    private List<TransitionRow> adjacentRoles(String profession) {
        return List.of(
                new TransitionRow("AI Strategy Specialist",    "Higher than baseline", "High",      "Medium",      "Works at the intersection of AI capability and business application — a growing category."),
                new TransitionRow("Operations Director",       "Higher than baseline", "High",      "Medium–High", "Oversees systems, people, and delivery at a level that requires human leadership."),
                new TransitionRow("Senior Consultant",         "Higher than baseline", "High",      "Medium",      "Advice-driven roles at senior level rely on trust, context, and judgement."),
                new TransitionRow("Product or Programme Lead", "Higher than baseline", "High",      "Medium",      "Ownership of complex, multi-stakeholder initiatives is strongly human-led."),
                new TransitionRow("People and Org Lead",       "Higher than baseline", "Very High", "Medium",      "Talent development, culture, and leadership remain very difficult to automate.")
        );
    }

    private List<String> thirtyDayPlan(String profession) {
        return List.of(
                "Audit your current work. Estimate what percentage is routine and rule-based versus strategic, interpersonal, or judgement-heavy.",
                "Start using AI tools deliberately in your daily workflow — document where they genuinely save time and where they still need heavy oversight.",
                "Update your CV and professional profile to emphasise outcomes, ownership, and problem-solving — not just tasks completed."
        );
    }

    private List<String> ninetyDayPlan(String profession) {
        return List.of(
                "Deepen one defensible area: specialist domain knowledge, stakeholder influence, AI tool fluency, or strategic planning.",
                "Build or document one piece of work that demonstrates judgement and ownership rather than task execution.",
                "Take on a cross-functional challenge that requires you to navigate ambiguity, people dynamics, and competing priorities."
        );
    }

    private List<String> yearPlan(String profession) {
        return List.of(
                "Reposition from 'person who completes tasks' to 'person who owns outcomes in complex environments'.",
                "Build evidence of leadership: mentoring, initiative ownership, stakeholder alignment, or strategic contribution.",
                "Move toward a track with greater resilience: specialist depth, AI-enabled delivery, advisory, or leadership."
        );
    }

    private List<String> warningSigns() {
        return List.of(
                "Most of your value comes from completing clearly defined tasks with limited room for judgement.",
                "You rarely participate in strategic discussions, stakeholder conversations, or decisions with real consequences.",
                "Your work is easy to describe as 'receive instruction, complete step, repeat'.",
                "You have little ownership of outcomes or accountability for results beyond your individual output.",
                "You have not yet integrated AI tools into your workflow, while peers increasingly have."
        );
    }

    private List<ResilienceRow> resilienceScorecard() {
        return List.of(
                new ResilienceRow("You handle ambiguous problems with incomplete information", "Strong signal"),
                new ResilienceRow("You own outcomes, not just tasks",                          "Strong signal"),
                new ResilienceRow("You work directly with stakeholders or clients",            "Strong signal"),
                new ResilienceRow("You make decisions that others rely on",                    "Strong signal"),
                new ResilienceRow("You use AI to increase your speed and quality",             "Strong signal"),
                new ResilienceRow("You have specialist depth that is hard to replicate",       "Strong signal")
        );
    }

    private String positioningAdvice(String profession, String riskLevel) {
        return "Do not position yourself primarily as someone who completes " + profession.toLowerCase()
                + " tasks. Position yourself as someone who solves hard problems, navigates ambiguity, "
                + "translates complexity into clear decisions, and uses AI to increase your speed, quality, "
                + "and leverage. In the next phase of the market, professionals who operate as outcome owners "
                + "and trusted advisors are likely to outperform those whose value is defined mainly by task throughput.";
    }

    private String normalise(String value) {
        return value == null ? "" : value.trim();
    }
}