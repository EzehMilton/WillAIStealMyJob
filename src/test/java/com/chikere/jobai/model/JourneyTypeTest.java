package com.chikere.jobai.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JourneyTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"profession", "professional", "PROFESSIONAL", " Profession "})
    void fromMode_acceptsProfessionalAliases(String mode) {
        assertEquals(JourneyType.PROFESSIONAL, JourneyType.fromMode(mode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"course", "student", "university_student", "UNIVERSITY_STUDENT", " University_Student "})
    void fromMode_acceptsUniversityStudentAliases(String mode) {
        assertEquals(JourneyType.UNIVERSITY_STUDENT, JourneyType.fromMode(mode));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a_level", "a-level", "alevel", "A_LEVEL_UNDECIDED", " A-Level "})
    void fromMode_acceptsALevelUndecidedAliases(String mode) {
        assertEquals(JourneyType.A_LEVEL_UNDECIDED, JourneyType.fromMode(mode));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown", "graduate", "not-a-real-mode"})
    void fromMode_defaultsBlankAndUnknownToProfessional(String mode) {
        assertEquals(JourneyType.PROFESSIONAL, JourneyType.fromMode(mode));
    }

    @Test
    void legacyMode_preservesExistingModeStringsForCurrentJourneys() {
        assertEquals("profession", JourneyType.PROFESSIONAL.legacyMode());
        assertEquals("course", JourneyType.UNIVERSITY_STUDENT.legacyMode());
        assertEquals("a_level", JourneyType.A_LEVEL_UNDECIDED.legacyMode());
    }
}
