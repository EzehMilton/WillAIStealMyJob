package com.chikere.jobai.service;

import com.chikere.jobai.model.PremiumReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample is rendered through the real premium-report template (§6.2) — this proves the
 * canned fixture actually satisfies that template (every table/list it reads is populated)
 * and that the sample banner appears without a leave-reminder modal for a page there's
 * nothing to lose by leaving.
 */
@SpringBootTest
class SampleReportFactoryTest {

    @Autowired
    private SampleReportFactory sampleReportFactory;

    @Autowired
    private SpringTemplateEngine templateEngine;

    @Test
    void sampleRendersThroughRealTemplateWithSampleBannerAndNoLeaveModal() {
        PremiumReport report = sampleReportFactory.sampleReport();

        Context context = new Context(Locale.UK);
        context.setVariable("report", report);
        context.setVariable("reportId", "sample");
        context.setVariable("reportLocked", false);
        context.setVariable("paymentStatus", null);
        context.setVariable("expiresAt", null);
        context.setVariable("checkoutState", null);
        context.setVariable("isSample", true);

        String html = templateEngine.process("premium-report", context);

        assertTrue(html.contains("Sample report."));
        assertTrue(html.contains("Software Developer"));
        assertTrue(html.contains("Task Exposure Map"));
        assertFalse(html.contains("id=\"leaveOverlay\""));
    }
}
