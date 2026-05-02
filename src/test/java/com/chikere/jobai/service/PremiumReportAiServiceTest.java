package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.PremiumReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PremiumReportAiServiceTest {

    private final JourneyConfigRegistry journeyConfigRegistry = new JourneyConfigRegistry(800, 450, 350);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildPremiumPrompt_usesProfessionalFramingForProfessionMode() {
        PremiumReportAiService service = newService();

        String prompt = service.buildPremiumPrompt(request("profession", "Accountant"));

        assertTrue(prompt.contains("Journey Type: PROFESSIONAL"));
        assertTrue(prompt.contains("Profession: Accountant"));
        assertTrue(prompt.contains("premiumScore"));
        assertTrue(prompt.contains("initial free-summary estimates"));
        assertTrue(prompt.contains("career survival plan"));
        assertTrue(prompt.contains("task-level automation"));
        assertTrue(prompt.contains("adjacent roles"));
    }

    @Test
    void buildPremiumPrompt_usesCourseFramingForCourseMode() {
        PremiumReportAiService service = newService();

        String prompt = service.buildPremiumPrompt(request("course", "Computer Science"));

        assertTrue(prompt.contains("Journey Type: UNIVERSITY_STUDENT"));
        assertTrue(prompt.contains("Course/Degree: Computer Science"));
        assertTrue(prompt.contains("degree and early-career strategy report"));
        assertTrue(prompt.contains("likely career paths"));
        assertTrue(prompt.contains("skills to build during the course"));
    }

    @Test
    void buildPremiumPrompt_usesStudyPathFramingForALevelMode() {
        PremiumReportAiService service = newService();

        String prompt = service.buildPremiumPrompt(request("a_level", "Maths, Biology, Psychology"));

        assertTrue(prompt.contains("Journey Type: A_LEVEL_UNDECIDED"));
        assertTrue(prompt.contains("Interests and Possible Subjects: Maths, Biology, Psychology"));
        assertTrue(prompt.contains("AI-ready study and career planning report"));
        assertTrue(prompt.contains("suggested subject/A-Level combinations"));
        assertTrue(prompt.contains("avoid pretending certainty"));
        assertTrue(prompt.contains("possible future career clusters"));
    }

    @Test
    void mapToReport_mapsPremiumScoreAndRiskFields() throws Exception {
        PremiumReportAiService service = newService();

        PremiumReport report = service.mapToReport(
                "report-123",
                request("profession", "Accountant"),
                objectMapper.readTree("""
                        {
                          "premiumScore": 7.4,
                          "premiumRiskLevel": "High",
                          "scoreRationale": "Routine work dominates the detailed exposure map."
                        }
                        """)
        );

        assertEquals(7.4, report.getPremiumScore());
        assertEquals("High", report.getPremiumRiskLevel());
        assertEquals("Routine work dominates the detailed exposure map.", report.getScoreRationale());
    }

    @Test
    void mapToReport_fallsBackToRequestScoreWhenPremiumScoreMissing() throws Exception {
        PremiumReportAiService service = newService();

        PremiumReport report = service.mapToReport(
                "report-123",
                request("profession", "Accountant"),
                objectMapper.readTree("{}")
        );

        assertEquals(5.5, report.getPremiumScore());
        assertEquals("Moderate", report.getPremiumRiskLevel());
    }

    @Test
    void mapToReport_clampsInvalidPremiumScoreSafely() throws Exception {
        PremiumReportAiService service = newService();

        PremiumReport highReport = service.mapToReport(
                "report-123",
                request("profession", "Accountant"),
                objectMapper.readTree("{\"premiumScore\": 12.7}")
        );
        PremiumReport lowReport = service.mapToReport(
                "report-456",
                request("profession", "Accountant"),
                objectMapper.readTree("{\"premiumScore\": -2.0}")
        );

        assertEquals(10.0, highReport.getPremiumScore());
        assertEquals("High", highReport.getPremiumRiskLevel());
        assertEquals(0.0, lowReport.getPremiumScore());
        assertEquals("Low", lowReport.getPremiumRiskLevel());
    }

    @Test
    void mapToReport_fallsBackWhenPremiumScoreIsNotNumeric() throws Exception {
        PremiumReportAiService service = newService();

        PremiumReport report = service.mapToReport(
                "report-123",
                request("profession", "Accountant"),
                objectMapper.readTree("{\"premiumScore\": \"not available\"}")
        );

        assertEquals(5.5, report.getPremiumScore());
        assertEquals("Moderate", report.getPremiumRiskLevel());
    }

    @Test
    void mapToReport_derivesPremiumRiskLevelWhenMissing() throws Exception {
        PremiumReportAiService service = newService();

        PremiumReport lowReport = service.mapToReport(
                "report-low",
                request("profession", "Accountant"),
                objectMapper.readTree("{\"premiumScore\": 3.9}")
        );
        PremiumReport moderateReport = service.mapToReport(
                "report-moderate",
                request("profession", "Accountant"),
                objectMapper.readTree("{\"premiumScore\": 3.0}")
        );
        PremiumReport highReport = service.mapToReport(
                "report-high",
                request("profession", "Accountant"),
                objectMapper.readTree("{\"premiumScore\": 7.0}")
        );

        assertEquals("Low", lowReport.getPremiumRiskLevel());
        assertEquals("Moderate", moderateReport.getPremiumRiskLevel());
        assertEquals("High", highReport.getPremiumRiskLevel());
    }

    private GenerateReportRequest request(String mode, String profession) {
        GenerateReportRequest request = new GenerateReportRequest();
        request.setMode(mode);
        request.setProfession(profession);
        request.setDescription("Generated from the free assessment summary.");
        request.setScore(5.5);
        request.setRiskLevel("MODERATE");
        return request;
    }

    private PremiumReportAiService newService() {
        return new PremiumReportAiService(
                mock(ChatClient.class),
                new GenerationMetricsService(
                        "gpt-5.4",
                        "gpt-5.4-mini",
                        2.5,
                        10.0,
                        0.4,
                        1.6
                ),
                journeyConfigRegistry,
                new DefaultResourceLoader(),
                "gpt-5.4-mini"
        );
    }
}
