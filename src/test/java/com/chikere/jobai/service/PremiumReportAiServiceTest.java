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
        assertTrue(prompt.contains("CRITICAL SCORE RULE"));
        assertTrue(prompt.contains("The official score is 5.5/10"));
        assertTrue(prompt.contains("Do not output premiumScore or premiumRiskLevel"));
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
    void mapToReport_usesOfficialRequestScoreAndRiskLevel() throws Exception {
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

        assertEquals(5.5, report.getScore());
        assertEquals("MODERATE", report.getRiskLevel());
        assertEquals(null, report.getPremiumScore());
        assertEquals(null, report.getPremiumRiskLevel());
        assertEquals("Routine work dominates the detailed exposure map.", report.getScoreRationale());
    }

    @Test
    void mapToReport_ignoresAiPremiumScoreWhenPresent() throws Exception {
        PremiumReportAiService service = newService();

        PremiumReport report = service.mapToReport(
                "report-123",
                request("profession", "Accountant"),
                objectMapper.readTree("""
                        {
                          "premiumScore": 9.9,
                          "premiumRiskLevel": "High",
                          "scoreRationale": "This explains the official score."
                        }
                        """)
        );

        assertEquals(5.5, report.getScore());
        assertEquals("MODERATE", report.getRiskLevel());
        assertEquals(null, report.getPremiumScore());
        assertEquals(null, report.getPremiumRiskLevel());
        assertEquals("This explains the official score.", report.getScoreRationale());
    }

    @Test
    void mapToReport_clampsOfficialRequestScoreSafely() throws Exception {
        PremiumReportAiService service = newService();
        GenerateReportRequest highRequest = request("profession", "Accountant");
        highRequest.setScore(12.7);
        GenerateReportRequest lowRequest = request("profession", "Accountant");
        lowRequest.setScore(-2.0);

        PremiumReport highReport = service.mapToReport(
                "report-123",
                highRequest,
                objectMapper.readTree("{}")
        );
        PremiumReport lowReport = service.mapToReport(
                "report-456",
                lowRequest,
                objectMapper.readTree("{}")
        );

        assertEquals(10.0, highReport.getScore());
        assertEquals(0.0, lowReport.getScore());
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
                        0.75,
                        4.50,
                        0.79
                ),
                journeyConfigRegistry,
                new DefaultResourceLoader(),
                "gpt-5.4-mini"
        );
    }
}
