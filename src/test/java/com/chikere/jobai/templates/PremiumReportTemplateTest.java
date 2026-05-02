package com.chikere.jobai.templates;

import com.chikere.jobai.model.PremiumReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PremiumReportTemplateTest {

    @Autowired
    private SpringTemplateEngine templateEngine;

    @Test
    void rendersProfessionCopy() {
        String html = render("profession", true);

        assertTrue(html.contains("Your AI Career Survival Plan"));
        assertTrue(html.contains("Profession"));
        assertTrue(html.contains("Software Developer"));
        assertTrue(html.contains("Task Exposure Map"));
        assertTrue(html.contains("Unlock my full report"));
        assertTrue(html.contains("4.99"));
    }

    @Test
    void lockedReportRendersBlurredPreviewAndClickableCta() {
        String html = render("profession", true);

        assertTrue(html.contains("premium-preview-shell"));
        assertTrue(html.contains("locked"));
        assertTrue(html.contains("blurred-content"));
        assertTrue(html.contains("locked-preview-overlay"));
        assertTrue(html.contains("Your full report is ready"));
        assertTrue(html.contains("Unlock detailed insights and your personalised plan"));
        assertTrue(html.contains("id=\"unlockReportBtnSecondary\""));
        assertTrue(html.contains("Current phase"));
        assertTrue(html.contains("AI literacy"));
        assertTrue(html.contains("Data analyst"));
        assertTrue(html.contains("Intro course"));
    }

    @Test
    void unlockedReportRendersNormalContentWithoutLockedOverlay() {
        String html = render("profession", false);

        assertTrue(html.contains("premium-preview-shell"));
        assertTrue(html.contains("Current phase"));
        assertTrue(html.contains("AI literacy"));
        assertTrue(html.contains("Data analyst"));
        assertTrue(html.contains("Intro course"));
        assertFalse(html.contains("premium-preview-shell locked"));
        assertFalse(html.contains("id=\"unlockReportBtnSecondary\""));
    }

    @Test
    void rendersCourseCopy() {
        String html = render("course", false);

        assertTrue(html.contains("Your AI Degree &amp; Career Strategy"));
        assertTrue(html.contains("Course"));
        assertTrue(html.contains("Computer Science"));
        assertTrue(html.contains("Career Path Exposure Map"));
        assertTrue(html.contains("Related Career Paths"));
        assertTrue(html.contains("Study") && html.contains("Career Action Plan"));
    }

    @Test
    void rendersALevelCopyWithoutProfessionDefaulting() {
        String lockedHtml = render("a_level", true);
        String unlockedHtml = render("a_level", false);

        assertTrue(lockedHtml.contains("Your AI-Ready Study &amp; Career Plan"));
        assertTrue(lockedHtml.contains("Subjects / Interests"));
        assertTrue(lockedHtml.contains("Maths, Biology, Psychology"));
        assertTrue(lockedHtml.contains("Future Path Exposure Map"));
        assertTrue(lockedHtml.contains("career clusters"));
        assertTrue(lockedHtml.contains("Unlock my full report"));
        assertTrue(lockedHtml.contains("0.99"));
        assertFalse(lockedHtml.contains("Your AI Career Survival Plan"));
        assertTrue(unlockedHtml.contains("Possible Career Clusters"));
        assertTrue(unlockedHtml.contains("Subject Choice &amp; Skills Action Plan"));
    }

    @Test
    void premiumReportHtmlUsesPremiumScoreWhenAvailable() {
        Context context = baseContext(false);
        context.setVariable("report", sampleReport("profession", true));

        String html = templateEngine.process("premium-report", context);

        assertTrue(html.contains("82%"));
        assertTrue(html.contains("High Risk"));
        assertTrue(html.contains("Detailed premium analysis found higher exposure."));
    }

    @Test
    void premiumReportHtmlFallsBackToScoreForOldReports() {
        String html = render("profession", false);

        assertTrue(html.contains("52%"));
        assertTrue(html.contains("Moderate Risk"));
        assertFalse(html.contains("Detailed premium analysis found higher exposure."));
    }

    @Test
    void premiumReportPdfUsesPremiumScoreWhenAvailable() {
        Context context = baseContext(false);
        context.setVariable("report", sampleReport("profession", true));

        String html = templateEngine.process("premium-report-pdf", context);

        assertTrue(html.contains("82%"));
        assertTrue(html.contains("High Risk"));
        assertTrue(html.contains("Detailed premium analysis found higher exposure."));
    }

    private String render(String mode, boolean reportLocked) {
        Context context = baseContext(reportLocked);
        context.setVariable("report", sampleReport(mode));
        return templateEngine.process("premium-report", context);
    }

    private Context baseContext(boolean reportLocked) {
        Context context = new Context(Locale.UK);
        context.setVariable("reportLocked", reportLocked);
        context.setVariable("reportId", "report-123");
        context.setVariable("checkoutState", "");
        context.setVariable("expiresAt", LocalDateTime.now().plusDays(1));
        return context;
    }

    private PremiumReport sampleReport(String mode) {
        return sampleReport(mode, false);
    }

    private PremiumReport sampleReport(String mode, boolean premiumScored) {
        String subject = switch (mode) {
            case "course" -> "Computer Science";
            case "a_level" -> "Maths, Biology, Psychology";
            default -> "Software Developer";
        };

        PremiumReport.PremiumReportBuilder builder = PremiumReport.builder()
                .reportId("report-123")
                .profession(subject)
                .mode(mode)
                .score(5.2)
                .riskLevel("Moderate")
                .generatedAt(LocalDateTime.now())
                .disruptionWindow("5-8 yrs")
                .adaptabilityPotential("High")
                .executiveSummary("A concise summary.")
                .coreAdvice("Build AI literacy.")
                .taskExposureMap(List.of(new PremiumReport.TaskRow("Analysis", 52, "Moderate", "1-3 yrs", "AI can assist this area.")))
                .timelineEvents(List.of(new PremiumReport.TimelineEvent("Now", "Current phase", "Description", List.of("AI"))))
                .skillCards(List.of(new PremiumReport.SkillCard("S", "AI literacy", "Use AI well.", "High", "Useful now")))
                .salaryTraditionalTitle("Traditional path")
                .salaryTraditionalMedian("£40k")
                .salaryTraditionalRange("Range: £30k-£50k")
                .salaryTraditionalBullets(List.of("Traditional option"))
                .salaryAiTitle("AI-augmented path")
                .salaryAiMedian("£55k")
                .salaryAiRange("Range: £45k-£70k")
                .salaryAiBullets(List.of("AI-literate option"))
                .consultancyOpportunity("Project work may exist.")
                .adjacentRoles(List.of(new PremiumReport.TransitionRow("Data analyst", "£35k-£55k", "High", "Medium", "Builds on existing skills.", List.of("Data"))))
                .thirtyDayPlan(List.of("Research options."))
                .ninetyDayPlan(List.of("Build a small project."))
                .yearPlan(List.of("Review progress."))
                .resources(List.of(new PremiumReport.ResourceCard("COURSE", "Intro course", "Useful resource.")))
                ;

        if (premiumScored) {
            builder
                    .premiumScore(8.2)
                    .premiumRiskLevel("High")
                    .scoreRationale("Detailed premium analysis found higher exposure.");
        }

        return builder.build();
    }
}
