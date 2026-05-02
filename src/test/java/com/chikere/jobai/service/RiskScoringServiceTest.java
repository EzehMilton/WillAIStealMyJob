package com.chikere.jobai.service;

import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskScoringResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScoringServiceTest {

    private final RiskScoringService scoringService = new RiskScoringService(
            new RiskDimensionCalculator(),
            new RiskAdjustmentService(),
            new RiskSanityValidator()
    );

    @Test
    void thresholdsUseBalancedScoringBands() {
        RiskSanityValidator validator = new RiskSanityValidator();

        assertEquals("Low", validator.riskLevel(3.4));
        assertEquals("Moderate", validator.riskLevel(3.5));
        assertEquals("Moderate", validator.riskLevel(6.9));
        assertEquals("High", validator.riskLevel(7.0));
    }

    @Test
    void sampleRolesProduceExpectedRiskLevels() {
        assertLevel("Low", "Choir singer", "Live performance, rehearsals, audience connection, and real-time coordination");
        assertLevel("Low", "Electrician", "Hands-on physical site work, fault finding, safety judgement, and unpredictable environments");
        assertLevel("Low", "CEO", "Leadership, strategy, negotiation, stakeholder trust, and accountability in uncertain conditions");
        assertLowToModerate("Nurse", "Patient care, safeguarding, empathy, documentation, ward coordination, and real-time judgement");
        assertLevel("Moderate", "Software developer", "Builds Java services, writes tests, debugs, documents releases, and maintains platforms");
        assertLevel("Moderate", "Graphic designer", "Creates brand concepts, visual assets, digital layouts, and client revisions");
        assertLevel("High", "Data entry clerk", "Routine data entry, repetitive forms, invoice processing, and standardised records");
        assertLevel("High", "Call center agent", "Scripted customer support, standard workflows, ticket handling, and repeatable responses");
    }

    @Test
    void courseAndALevelPathsUseSameScoringModel() {
        RiskScoringResult socialCare = scoringService.score(
                JourneyType.UNIVERSITY_STUDENT,
                "B.Sc. Social Care",
                "I want hands-on care work with safeguarding, empathy, care planning, and people support",
                "Generic model summary"
        );
        RiskScoringResult computing = scoringService.score(
                JourneyType.A_LEVEL_UNDECIDED,
                "Computer Science, Maths, Business",
                "I like coding, software, spreadsheets, business analysis, and digital work",
                "Generic model summary"
        );

        assertEquals("Low", socialCare.riskLevel());
        assertTrue(computing.score() > socialCare.score());
    }

    @Test
    void protectiveFactorsReduceFinalScoreBelowBaseScore() {
        RiskScoringResult singer = scoringService.score(
                JourneyType.PROFESSIONAL,
                "Choir singer",
                "Live performance, physical presence, real-time coordination, emotional audience connection, and unpredictable venues",
                "Generic model summary"
        );

        assertTrue(singer.protectiveAdjustment() >= 2.0);
        assertTrue(singer.score() < singer.baseScore());
    }

    @Test
    void summaryIsAlignedWithFinalRiskLevelAndDoesNotExposeNumericScore() {
        RiskScoringResult result = scoringService.score(
                JourneyType.PROFESSIONAL,
                "Choir singer",
                "Live performance, audience connection, rehearsals, and real-time human coordination",
                "This has moderate impact despite being hard to automate."
        );

        assertEquals("Low", result.riskLevel());
        assertTrue(result.summary().contains("Choir singer"));
        assertTrue(result.summary().contains("low AI impact"));
        assertFalse(result.summary().matches(".*\\d+(\\.\\d+)?/10.*"));
        assertFalse(result.summary().contains("%"));
    }

    private void assertLevel(String expectedLevel, String subject, String details) {
        RiskScoringResult result = scoringService.score(
                JourneyType.PROFESSIONAL,
                subject,
                details,
                "Generic model summary"
        );

        assertEquals(expectedLevel, result.riskLevel(), subject + " score=" + result.score());
    }

    private void assertLowToModerate(String subject, String details) {
        RiskScoringResult result = scoringService.score(
                JourneyType.PROFESSIONAL,
                subject,
                details,
                "Generic model summary"
        );

        assertTrue(
                "Low".equals(result.riskLevel()) || "Moderate".equals(result.riskLevel()),
                subject + " score=" + result.score()
        );
        assertTrue(result.score() >= 1.5, subject + " score should not imply no exposure");
    }
}
