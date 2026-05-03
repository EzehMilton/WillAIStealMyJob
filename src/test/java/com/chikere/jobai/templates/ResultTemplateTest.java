package com.chikere.jobai.templates;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
class ResultTemplateTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rendersJourneySpecificPrices() throws Exception {
        assertTrue(render("profession").contains("£4.99"));
        assertTrue(render("course").contains("£2.99"));
        assertTrue(render("a_level").contains("£0.99"));
    }

    @Test
    void freeResultDoesNotRenderVisiblePercentageScore() throws Exception {
        String html = render("profession", 5.9);

        assertFalse(html.contains("59%"));
        assertFalse(html.contains("0%</span>"));
        assertFalse(html.contains("100%</span>"));
        assertFalse(html.contains("risk-score-circle"));
        assertFalse(html.contains("progress-bar"));
        assertTrue(html.contains("Moderate Impact Detected"));
    }

    @Test
    void impactIndicatorPositionIsRenderedWithoutCssDivision() throws Exception {
        String lowHtml = render("profession", 2.0);
        String highHtml = render("profession", 8.0);

        assertTrue(lowHtml.contains("left: 21.2%;"));
        assertTrue(highHtml.contains("left: 78.8%;"));
        assertFalse(lowHtml.contains("/ 10"));
        assertFalse(highHtml.contains("/ 10"));
    }

    @Test
    void rendersImpactLabelsFromScoreThresholds() throws Exception {
        assertTrue(render("profession", 3.4).contains("Low Impact Detected"));
        assertTrue(render("profession", 3.5).contains("Moderate Impact Detected"));
        assertTrue(render("profession", 6.9).contains("Moderate Impact Detected"));
        assertTrue(render("profession", 7.0).contains("High Impact Detected"));
    }

    @Test
    void rendersJourneySpecificImpactCopy() throws Exception {
        String professionHtml = render("profession", 5.0);
        String courseHtml = render("course", 5.0);
        String aLevelHtml = render("a_level", 5.0);

        assertTrue(professionHtml.contains("Your AI Impact Zone"));
        assertTrue(professionHtml.contains("This is a quick snapshot of how AI may affect your current role."));
        assertTrue(professionHtml.contains("AI Impact"));
        assertTrue(professionHtml.contains("Unlock the exact exposure, task map, and 30/90/365-day plan."));

        assertTrue(courseHtml.contains("Your Degree Impact Zone"));
        assertTrue(courseHtml.contains("Degree Path Exposure"));
        assertTrue(courseHtml.contains("career paths linked to this course"));
        assertTrue(courseHtml.contains("Unlock the exact exposure, career-path map, and study strategy."));

        assertTrue(aLevelHtml.contains("Your Future Path Impact Zone"));
        assertTrue(aLevelHtml.contains("Future Path Exposure"));
        assertTrue(aLevelHtml.contains("study and career paths connected to your subject interests"));
        assertTrue(aLevelHtml.contains("Unlock the exact exposure, subject-path map, and next steps."));
        assertTrue(aLevelHtml.contains("View premium breakdown"));
    }

    @Test
    void resultPayloadIncludesOriginalDetailsForReportGeneration() throws Exception {
        assertTrue(render("profession").contains("originalDetails"));
        assertTrue(render("profession").contains("Detailed intake text"));
    }

    @Test
    void checkoutPayloadStillIncludesReportGenerationContext() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/result.html"));

        assertTrue(template.contains("score:"));
        assertTrue(template.contains("riskLevel:"));
        assertTrue(template.contains("summary:"));
        assertTrue(template.contains("assessment:"));
        assertTrue(template.contains("originalDetails:"));
    }

    @Test
    void generatingReportPrefersOriginalDetailsForDescription() throws Exception {
        String template = Files.readString(Path.of("src/main/resources/templates/generating-report.html"));

        assertTrue(template.contains("description: stored.originalDetails || stored.details || stored.assessment || stored.summary || ''"));
    }

    private String render(String mode) throws Exception {
        return render(mode, 5.0);
    }

    private String render(String mode, double score) throws Exception {
        return mockMvc.perform(get("/result")
                        .flashAttr("mode", mode)
                        .flashAttr("profession", "Example")
                        .flashAttr("score", score)
                        .flashAttr("riskLevel", "Moderate")
                        .flashAttr("summary", "Summary")
                        .flashAttr("assessment", "Assessment")
                        .flashAttr("originalDetails", "Detailed intake text")
                        .flashAttr("success", true))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
