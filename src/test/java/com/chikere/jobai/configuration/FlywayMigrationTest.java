package com.chikere.jobai.configuration;

import com.chikere.jobai.model.PaymentStatus;
import com.chikere.jobai.model.ReportRequest;
import com.chikere.jobai.model.ReportStatus;
import com.chikere.jobai.repository.ReportRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boots the app with the schema created by the Flyway baseline (not Hibernate) and
 * ddl-auto=validate — exactly like production. A drift between V1__baseline.sql and the
 * JPA entities fails this test at context startup.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:flyway-validate;DB_CLOSE_DELAY=-1"
})
class FlywayMigrationTest {

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @Test
    void migratedSchemaAcceptsAFullReportRow() {
        ReportRequest report = new ReportRequest();
        report.setId(UUID.randomUUID());
        report.setProfession("Java Developer");
        report.setMode("profession");
        report.setRiskScore(5.5);
        report.setRiskLevel("Moderate");
        report.setPaymentStatus(PaymentStatus.PENDING);
        report.setGenerationStatus(ReportStatus.COMPLETED);
        report.setRequestHash("a".repeat(64));
        report.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(24));
        report.setReportJson("{\"reportId\":\"x\",\"content\":\"" + "y".repeat(50_000) + "\"}");

        reportRequestRepository.save(report);

        ReportRequest loaded = reportRequestRepository.findById(report.getId()).orElseThrow();
        assertEquals("Java Developer", loaded.getProfession());
        assertEquals(report.getReportJson(), loaded.getReportJson());
    }
}
