package com.chikere.jobai.templates;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The visitor bootstrap and CSRF helpers live once in /static/js — no template may carry
 * its own copy again (§6.1: a bug fix must never need applying in four places).
 */
class SharedScriptsTemplateTest {

    private static final List<String> FUNNEL_TEMPLATES = List.of(
            "index.html", "result.html", "generating-report.html", "premium-report.html");

    @Test
    void sharedJsFilesProvideThePlumbing() throws Exception {
        String visitorJs = Files.readString(Path.of("src/main/resources/static/js/visitor.js"));
        String analyticsJs = Files.readString(Path.of("src/main/resources/static/js/analytics.js"));

        assertTrue(visitorJs.contains("window.visitorId"));
        assertTrue(visitorJs.contains("function csrfHeaders"));
        assertTrue(visitorJs.contains("function csrfToken"));
        assertTrue(visitorJs.contains("function csrfHeaderName"));
        assertTrue(analyticsJs.contains("function trackEvent"));
        assertTrue(analyticsJs.contains("fetch('/analytics/event'"));
    }

    @Test
    void funnelTemplatesIncludeVisitorJsAndDefineNoLocalCopies() throws Exception {
        for (String name : FUNNEL_TEMPLATES) {
            String template = Files.readString(Path.of("src/main/resources/templates", name));

            assertTrue(template.contains("<script src=\"/js/visitor.js\"></script>"),
                    name + " must include visitor.js");
            assertFalse(template.contains("const KEY = 'visitor_id'"),
                    name + " must not carry its own visitor bootstrap");
            assertFalse(template.contains("function csrfHeaders")
                            || template.contains("function csrfToken")
                            || template.contains("function csrfHeaderName"),
                    name + " must not define local CSRF helpers");
        }
    }

    @Test
    void analyticsPagesUseTheSharedTracker() throws Exception {
        for (String name : List.of("index.html", "result.html")) {
            String template = Files.readString(Path.of("src/main/resources/templates", name));

            assertTrue(template.contains("<script src=\"/js/analytics.js\"></script>"),
                    name + " must include analytics.js");
            assertFalse(template.contains("fetch('/analytics/event'"),
                    name + " must go through trackEvent, not raw fetch");
            assertTrue(template.contains("trackEvent("), name + " should track funnel events");
        }
    }
}
