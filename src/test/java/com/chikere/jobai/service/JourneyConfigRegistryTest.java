package com.chikere.jobai.service;

import com.chikere.jobai.model.JourneyConfig;
import com.chikere.jobai.model.JourneyType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JourneyConfigRegistryTest {

    private final JourneyConfigRegistry registry = new JourneyConfigRegistry(800, 450, 350);

    @Test
    void professionalConfig_matchesExistingProfessionBehaviour() {
        JourneyConfig config = registry.get(JourneyType.PROFESSIONAL);

        assertEquals(JourneyType.PROFESSIONAL, config.journeyType());
        assertEquals("profession", config.legacyModeValue());
        assertEquals("Profession", config.subjectLabel());
        assertEquals("Role Details", config.detailsLabel());
        assertEquals(800, config.wordLimit());
        assertTrue(config.cvUploadAllowed());
        assertTrue(config.manualInputAllowed());
        assertEquals("£4.99", config.premiumPriceDisplay());
        assertEquals("profession-instructionsV2.txt", config.promptInstructionResource());
    }

    @Test
    void universityStudentConfig_matchesExistingCourseBehaviour() {
        JourneyConfig config = registry.get(JourneyType.UNIVERSITY_STUDENT);

        assertEquals(JourneyType.UNIVERSITY_STUDENT, config.journeyType());
        assertEquals("course", config.legacyModeValue());
        assertEquals("Course/Degree", config.subjectLabel());
        assertEquals("Expected Career Path", config.detailsLabel());
        assertEquals(450, config.wordLimit());
        assertFalse(config.cvUploadAllowed());
        assertTrue(config.manualInputAllowed());
        assertEquals("£2.99", config.premiumPriceDisplay());
        assertEquals("course-instructionsV2.txt", config.promptInstructionResource());
    }

    @Test
    void aLevelUndecidedConfig_matchesFrontendALevelMode() {
        JourneyConfig config = registry.get(JourneyType.A_LEVEL_UNDECIDED);

        assertEquals(JourneyType.A_LEVEL_UNDECIDED, config.journeyType());
        assertEquals("a_level", config.legacyModeValue());
        assertEquals("Interests and Possible Subjects", config.subjectLabel());
        assertEquals("Tell us about your interests, strengths, and future goals", config.detailsLabel());
        assertEquals(350, config.wordLimit());
        assertFalse(config.cvUploadAllowed());
        assertTrue(config.manualInputAllowed());
        assertEquals("£0.99", config.premiumPriceDisplay());
        assertEquals("a-level-instructionsV3.txt", config.promptInstructionResource());
    }

    @Test
    void getByLegacyMode_usesJourneyTypeParsingAliases() {
        assertEquals(JourneyType.PROFESSIONAL, registry.get("professional").journeyType());
        assertEquals(JourneyType.UNIVERSITY_STUDENT, registry.get("student").journeyType());
        assertEquals(JourneyType.A_LEVEL_UNDECIDED, registry.get("a-level").journeyType());
        assertEquals(JourneyType.PROFESSIONAL, registry.get("unknown").journeyType());
    }

    @Test
    void cvUploadIsAllowedOnlyForProfessional() {
        assertTrue(registry.get(JourneyType.PROFESSIONAL).cvUploadAllowed());
        assertFalse(registry.get(JourneyType.UNIVERSITY_STUDENT).cvUploadAllowed());
        assertFalse(registry.get(JourneyType.A_LEVEL_UNDECIDED).cvUploadAllowed());
    }

    @Test
    void wordLimitsResolveForAllJourneys() {
        assertEquals(800, registry.get("profession").wordLimit());
        assertEquals(450, registry.get("course").wordLimit());
        assertEquals(350, registry.get("a_level").wordLimit());
    }
}
