package com.chikere.jobai.templates;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratingReportTemplateTest {

    @Test
    void templateContainsJourneyAwareLoadingCopy() throws Exception {
        String template = template();

        assertTrue(template.contains("const journeyCopy = {"));
        assertTrue(template.contains("Building your personalised AI Career Survival Plan"));
        assertTrue(template.contains("Analyzing profession and tasks"));
        assertTrue(template.contains("Building your personalised AI Degree & Career Strategy"));
        assertTrue(template.contains("Analyzing your course and career direction"));
        assertTrue(template.contains("Building your personalised AI-Ready Study & Career Plan"));
        assertTrue(template.contains("Understanding your interests and subject ideas"));
    }

    @Test
    void templateKeepsGenerateReportPayloadAndRedirectBehaviour() throws Exception {
        String template = template();

        assertTrue(template.contains("sessionStorage.getItem('checkoutPayload')"));
        assertTrue(template.contains("fetch('/generate-report'"));
        assertTrue(template.contains("description: stored.originalDetails || stored.details || stored.assessment || stored.summary || ''"));
        assertTrue(template.contains("mode:        stored.mode        || 'profession'"));
        assertTrue(template.contains("window.location.href = '/premium-report/' + reportId"));
    }

    @Test
    void templatePollsGenerationStatusUntilCompleted() throws Exception {
        String template = template();

        assertTrue(template.contains("await waitUntilReady(data.reportId);"));
        assertTrue(template.contains("fetch('/report/' + id + '/status')"));
        assertTrue(template.contains("if (status.reportStatus === 'COMPLETED') return;"));
        assertTrue(template.contains("if (status.reportStatus === 'FAILED') throw new Error"));
    }

    @Test
    void generationFailureShowsErrorStateOnceAnimationCompletes() throws Exception {
        String template = template();

        // Both the animation-done and fetch-done paths must resolve through maybeFinish,
        // which shows the error state when no reportId was obtained — a failed fetch must
        // never leave the user on an endless spinner.
        assertTrue(template.contains("function maybeFinish()"));
        assertTrue(template.contains("if (!animationDone || !apiDone) return;"));
        assertTrue(template.contains("showError();"));
        assertTrue(template.matches("(?s).*catch \\(err\\).*apiDone = true;\\s*maybeFinish\\(\\);.*"));
    }

    private String template() throws Exception {
        return Files.readString(Path.of("src/main/resources/templates/generating-report.html"));
    }
}
