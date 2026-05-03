package com.chikere.jobai.model;

import java.util.Locale;

public enum JourneyType {
    PROFESSIONAL("profession"),
    UNIVERSITY_STUDENT("course"),
    A_LEVEL_UNDECIDED("a_level");

    private final String legacyMode;

    JourneyType(String legacyMode) {
        this.legacyMode = legacyMode;
    }

    public String legacyMode() {
        return legacyMode;
    }

    public boolean isProfessional() {
        return this == PROFESSIONAL;
    }

    public boolean isUniversityStudent() {
        return this == UNIVERSITY_STUDENT;
    }

    public static JourneyType fromMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return PROFESSIONAL;
        }

        String normalized = mode.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_');

        return switch (normalized) {
            case "profession", "professional" -> PROFESSIONAL;
            case "course", "student", "university_student" -> UNIVERSITY_STUDENT;
            case "a_level", "alevel", "a_level_undecided" -> A_LEVEL_UNDECIDED;
            default -> PROFESSIONAL;
        };
    }
}
