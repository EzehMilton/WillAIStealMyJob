package com.chikere.jobai.templates;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With 'unsafe-inline' gone from script-src (§3.2), every inline script needs the request
 * nonce and no element may use inline on* handler attributes — either would be silently
 * blocked by the browser. premium-report-pdf.html is excluded (rendered for PDF, not served).
 * sample-report.html no longer exists — /sample-report now renders premium-report.html (§6.2).
 */
class CspTemplateTest {

    private static final List<String> SERVED_TEMPLATES = List.of(
            "index.html", "result.html", "generating-report.html",
            "premium-report.html", "error.html");

    private static final Pattern SCRIPT_OPEN_TAG = Pattern.compile("<script\\b[^>]*>");
    private static final Pattern INLINE_HANDLER =
            Pattern.compile("\\son(click|submit|load|change|input|keyup|mouseover|mouseout|error)\\s*=");

    @Test
    void everyInlineScriptCarriesTheCspNonce() throws Exception {
        for (String name : SERVED_TEMPLATES) {
            String template = template(name);
            Matcher scripts = SCRIPT_OPEN_TAG.matcher(template);
            while (scripts.find()) {
                String tag = scripts.group();
                if (tag.contains("src=")) {
                    continue; // external scripts pass via 'self'/host allowlist
                }
                assertTrue(tag.contains("nonce=${cspNonce}"),
                        name + " has an inline script without the CSP nonce: " + tag);
            }
        }
    }

    @Test
    void noTemplateUsesInlineEventHandlerAttributes() throws Exception {
        for (String name : SERVED_TEMPLATES) {
            Matcher handlers = INLINE_HANDLER.matcher(template(name));
            assertFalse(handlers.find(),
                    name + " uses an inline on* handler (blocked by CSP): "
                            + (handlers.hitEnd() ? "" : handlers.group()));
        }
    }

    private String template(String name) throws Exception {
        return Files.readString(Path.of("src/main/resources/templates", name));
    }
}
