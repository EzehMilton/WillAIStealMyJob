package com.chikere.jobai.service;

import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.model.PremiumReport.ResourceCard;
import com.chikere.jobai.model.PremiumReport.SkillCard;
import com.chikere.jobai.model.PremiumReport.TaskRow;
import com.chikere.jobai.model.PremiumReport.TimelineEvent;
import com.chikere.jobai.model.PremiumReport.TransitionRow;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Canned PremiumReport shown at /sample-report. Rendered through the real premium-report
 * template (§6.2), so the sample can never drift from what customers actually receive.
 */
@Service
public class SampleReportFactory {

    public PremiumReport sampleReport() {
        return PremiumReport.builder()
                .reportId("sample")
                .profession("Software Developer")
                .mode("profession")
                .score(5.6)
                .riskLevel("Moderate")
                .scoreRationale("Routine implementation and documentation are already automatable, "
                        + "while architecture, stakeholder alignment, and production ownership remain human-led.")
                .generatedAt(LocalDateTime.now())

                .disruptionWindow("5–8 yrs")
                .adaptabilityPotential("High")
                .mostExposedArea("Routine feature implementation")
                .safestDirection("AI-augmented engineering leadership")
                .coreAdvice("Move from writing every line yourself to directing, reviewing, and owning "
                        + "AI-assisted delivery — judgement and ownership are the durable skills.")

                .executiveSummary("AI coding assistants already draft boilerplate, tests, and routine "
                        + "features faster than most developers. Over the next five to eight years that "
                        + "pressure moves up the stack: the developers who thrive will be those who frame "
                        + "problems, review and integrate AI output safely, and own outcomes in production. "
                        + "Your adaptability potential is high — the transition is very achievable if you "
                        + "start repositioning now.")
                .positioningAdvice("Position yourself as the engineer who multiplies a team with AI rather "
                        + "than competes against it: own system design, review AI-generated changes with "
                        + "authority, and become the person accountable for what ships.")

                .taskExposureMap(List.of(
                        new TaskRow("Boilerplate & CRUD implementation", 85, "High", "Now",
                                "AI assistants generate this reliably today; it is already commoditised."),
                        new TaskRow("Unit tests & documentation", 75, "High", "Now",
                                "Structured, pattern-heavy writing is exactly what models do best."),
                        new TaskRow("Debugging & incident response", 45, "Moderate", "3–5 yrs",
                                "AI helps localise faults, but production context and judgement stay human."),
                        new TaskRow("System design & architecture", 25, "Low", "5–8 yrs",
                                "Trade-off decisions across teams, budgets, and legacy constraints resist automation."),
                        new TaskRow("Stakeholder communication", 15, "Low", "8+ yrs",
                                "Trust, negotiation, and accountability are human-anchored.")
                ))

                .timelineEvents(List.of(
                        new TimelineEvent("Now", "Assistants absorb routine coding",
                                "Teams expect AI-assisted output as the baseline; raw typing speed stops differentiating.",
                                List.of("Copilots", "Codegen")),
                        new TimelineEvent("1–3 yrs", "AI agents take whole tickets",
                                "Well-specified tasks go end-to-end to agents; review and integration become the bottleneck skills.",
                                List.of("Agents", "Code review")),
                        new TimelineEvent("3–8 yrs", "Teams restructure around AI leverage",
                                "Smaller teams ship more; roles concentrate around architecture, product judgement, and ownership.",
                                List.of("Team design", "Ownership"))
                ))

                .skillCards(List.of(
                        new SkillCard("A", "AI-assisted delivery", "Direct, review, and integrate AI-generated "
                                + "code safely at speed.", "High", "Table stakes within 2 years"),
                        new SkillCard("S", "System design", "Own architecture decisions and their trade-offs "
                                + "across services and teams.", "High", "Durable differentiator"),
                        new SkillCard("P", "Production ownership", "Be accountable for reliability, security, "
                                + "and cost of what ships.", "Medium", "Hard to automate"),
                        new SkillCard("C", "Stakeholder communication", "Translate between business intent and "
                                + "technical reality.", "Medium", "Compounds every other skill")
                ))

                .adjacentRoles(List.of(
                        new TransitionRow("AI Engineering Lead", "Higher", "High", "Medium",
                                "Same technical base, plus directing AI-augmented delivery.",
                                List.of("LLM tooling", "Team leadership")),
                        new TransitionRow("Platform / DevOps Engineer", "Similar", "High", "Low",
                                "Infrastructure judgement and incident ownership resist automation.",
                                List.of("Cloud architecture", "Observability")),
                        new TransitionRow("Solutions Architect", "Higher", "High", "Medium",
                                "Client-facing design work anchored in trust and trade-offs.",
                                List.of("System design", "Consulting"))
                ))

                .salaryTraditionalTitle("Traditional Software Developer")
                .salaryTraditionalMedian("$95k")
                .salaryTraditionalRange("Range: $70k–$130k")
                .salaryTraditionalBullets(List.of(
                        "Demand for purely routine implementation is flattening",
                        "Compensation increasingly tied to AI-leveraged output"))
                .salaryAiTitle("AI-Augmented Software Engineer")
                .salaryAiMedian("$130k")
                .salaryAiRange("Range: $100k–$180k")
                .salaryAiBullets(List.of(
                        "Premium for engineers who ship multiples with AI tooling",
                        "Leadership of AI-assisted teams commands the top of the band"))
                .consultancyOpportunity("Experienced developers who can introduce AI-assisted delivery to "
                        + "slower-moving teams are commanding strong day rates.")

                .aiStrengths(List.of(
                        "Generating well-scoped, pattern-based code in seconds",
                        "Producing tests, documentation, and migrations at scale",
                        "Refactoring within clearly defined boundaries"))
                .humanAdvantages(List.of(
                        "Framing ambiguous problems before any code is written",
                        "Owning production outcomes and being accountable for failures",
                        "Earning stakeholder trust across teams"))
                .warningSigns(List.of(
                        "Your tickets are mostly well-specified CRUD work",
                        "You rarely review or integrate others' (or AI's) code",
                        "Your role has no contact with users, stakeholders, or production"))

                .thirtyDayPlan(List.of(
                        "Adopt an AI coding assistant as your daily default and learn its failure modes",
                        "Volunteer for code review duty on your team",
                        "Document one system you own end-to-end"))
                .ninetyDayPlan(List.of(
                        "Lead the design of one feature spanning multiple services",
                        "Introduce an AI-assisted workflow improvement your team adopts",
                        "Take on-call ownership for a production service"))
                .yearPlan(List.of(
                        "Own an architectural decision with organisation-level impact",
                        "Become your team's reference point for AI-assisted delivery",
                        "Mentor two engineers on judgement-heavy skills"))

                .resources(List.of(
                        new ResourceCard("Course", "AI-assisted engineering practices",
                                "Hands-on patterns for directing and reviewing AI-generated code."),
                        new ResourceCard("Book", "Designing Data-Intensive Applications",
                                "The judgement layer of engineering that AI cannot replace."),
                        new ResourceCard("Practice", "Architecture katas",
                                "Regular design exercises to build the trade-off muscle.")
                ))

                .build();
    }
}
