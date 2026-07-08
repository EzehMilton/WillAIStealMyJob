package com.chikere.jobai.configuration;

import com.chikere.jobai.model.AnalyticsEvent;
import com.chikere.jobai.model.PaymentStatus;
import com.chikere.jobai.model.ReportRequest;
import com.chikere.jobai.model.ReportStatus;
import com.chikere.jobai.repository.AnalyticsEventRepository;
import com.chikere.jobai.repository.ReportRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves the Flyway baseline + ddl-auto=validate + entity mappings work on real
 * PostgreSQL — the production target. Skipped automatically when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class PostgresFlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @Autowired
    private AnalyticsEventRepository analyticsEventRepository;

    @Test
    void reportRoundTripsThroughPostgres() {
        ReportRequest report = new ReportRequest();
        report.setId(UUID.randomUUID());
        report.setProfession("Java Developer");
        report.setMode("profession");
        report.setRiskScore(5.5);
        report.setRiskLevel("Moderate");
        report.setPaymentStatus(PaymentStatus.PENDING);
        report.setGenerationStatus(ReportStatus.GENERATING);
        report.setRequestHash("b".repeat(64));
        report.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(24));
        report.setReportJson("{\"content\":\"" + "z".repeat(50_000) + "\"}");

        reportRequestRepository.save(report);

        ReportRequest loaded = reportRequestRepository.findById(report.getId()).orElseThrow();
        assertEquals(PaymentStatus.PENDING, loaded.getPaymentStatus());
        assertEquals(ReportStatus.GENERATING, loaded.getGenerationStatus());
        assertEquals(report.getReportJson(), loaded.getReportJson());
    }

    @Test
    void analyticsEventRoundTripsThroughPostgres() {
        AnalyticsEvent event = new AnalyticsEvent();
        event.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
        event.setEventType("payment_completed");
        event.setVisitorId("visitor-1");
        event.setAmountCents(900L);
        event.setCurrency("gbp");

        AnalyticsEvent saved = analyticsEventRepository.save(event);

        AnalyticsEvent loaded = analyticsEventRepository.findById(saved.getId()).orElseThrow();
        assertEquals("payment_completed", loaded.getEventType());
        assertEquals(900L, loaded.getAmountCents());
    }
}
