package com.chikere.jobai.service;

import com.chikere.jobai.model.PremiumReport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReportPreviewServiceTest {

    @Test
    void buildLockedPreviewPreservesPremiumScoreFields() {
        PremiumReport report = PremiumReport.builder()
                .reportId("report-123")
                .profession("Software Developer")
                .mode("profession")
                .score(5.2)
                .riskLevel("Moderate")
                .premiumScore(8.1)
                .premiumRiskLevel("High")
                .scoreRationale("Detailed analysis found higher exposure.")
                .build();

        PremiumReport preview = new ReportPreviewService().buildLockedPreview(report);

        assertEquals(8.1, preview.getPremiumScore());
        assertEquals("High", preview.getPremiumRiskLevel());
        assertEquals("Detailed analysis found higher exposure.", preview.getScoreRationale());
    }
}
