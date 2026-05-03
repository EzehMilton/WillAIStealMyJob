package com.chikere.jobai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class JobAiServiceTest {

    private final JourneyConfigRegistry journeyConfigRegistry = new JourneyConfigRegistry(800, 450, 350);

    @Test
    void buildAssessmentPrompt_usesALevelInstructionsForALevelMode() {
        JobAiService service = newService();

        String prompt = service.buildAssessmentPrompt(
                "a_level",
                "Maths, Biology, Psychology",
                "I like science, people, and problem solving"
        );

        assertTrue(prompt.contains("Year 11, GCSE, or A-Level student"));
        assertTrue(prompt.contains("Interests and Possible Subjects"));
        assertTrue(prompt.contains("Tell us about your interests, strengths, and future goals"));
        assertTrue(prompt.contains("2-3 sentences, suitable for display as a free snapshot insight card"));
        assertTrue(prompt.contains("DO NOT include exact numeric scores"));
    }

    @Test
    void buildAssessmentPrompt_keepsProfessionInstructionsForProfessionMode() {
        JobAiService service = newService();

        String prompt = service.buildAssessmentPrompt(
                "profession",
                "Teacher",
                "I plan lessons and support students"
        );

        assertTrue(prompt.contains("assess how likely a specific profession is to be impacted by AI"));
        assertTrue(prompt.contains("Profession"));
        assertTrue(prompt.contains("Role Details"));
    }

    @Test
    void buildAssessmentPrompt_keepsCourseInstructionsForCourseMode() {
        JobAiService service = newService();

        String prompt = service.buildAssessmentPrompt(
                "course",
                "Computer Science",
                "I want to become a software engineer"
        );

        assertTrue(prompt.contains("advise a student who is considering a specific course or degree"));
        assertTrue(prompt.contains("Course/Degree"));
        assertTrue(prompt.contains("Expected Career Path"));
    }

    @Test
    void promptExplainsDimensionBasedScoringAndSanityCheck() {
        JobAiService service = newService();

        String prompt = service.buildAssessmentPrompt(
                "profession",
                "Choir singer",
                "Live performance, rehearsals, audience connection, and real-time coordination"
        );

        assertTrue(prompt.contains("Task Repeatability"));
        assertTrue(prompt.contains("Digital vs Physical Execution"));
        assertTrue(prompt.contains("Protective factors must reduce the final score"));
        assertTrue(prompt.contains("0.0 – 3.4"));
        assertTrue(prompt.contains("Never return a summary that contradicts the score or riskLevel"));
    }

    @Test
    void liveSummaryScoreClampKeepsParsedScoresInRange() {
        JobAiService service = newService();

        assertEquals(0.0, service.clampScore(-1.2));
        assertEquals(10.0, service.clampScore(12.3));
        assertEquals(4.6, service.clampScore(4.6));
    }

    private JobAiService newService() {
        return new JobAiService(
                mock(ChatClient.class),
                new DefaultResourceLoader(),
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
                new RiskScoringService(
                        new RiskDimensionCalculator(),
                        new RiskAdjustmentService(),
                        new RiskSanityValidator()
                ),
                "gpt-5.4-mini"
        );
    }
}
