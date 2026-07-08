package com.chikere.jobai.templates;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HowTemplateTest {

    @Test
    void homePageLinksToHowItWorksInASeparateWindow() throws Exception {
        String index = Files.readString(Path.of("src/main/resources/templates/index.html"));

        assertTrue(index.contains("How it works"));
        assertTrue(index.contains("th:href=\"@{/how}\""));
        assertTrue(index.contains("target=\"_blank\""));
        assertTrue(index.contains("rel=\"noopener\""));
    }

    @Test
    void howPageExplainsTheProcessWithoutExposingProprietaryInternals() throws Exception {
        String how = Files.readString(Path.of("src/main/resources/templates/how.html"));

        assertTrue(how.contains("A clear, personalised view of AI risk"));
        assertTrue(how.contains("We start with your situation"));
        assertTrue(how.contains("We assess exposure at the practical level"));
        assertTrue(how.contains("We turn the findings into a plan"));
        assertTrue(how.contains("proprietary"));
        assertFalse(how.toLowerCase().contains("prompt template"));
        assertFalse(how.toLowerCase().contains("scoring formula"));
    }
}
