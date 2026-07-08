package com.chikere.jobai.configuration;

import com.chikere.jobai.model.ReportRequest;
import com.chikere.jobai.repository.ReportRequestRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces the pre-Flyway upgrade path: a database created by the old ddl-auto=update
 * schema (report_json as CLOB, no generation_status/request_hash, no analytics_events)
 * containing data. baseline-on-migrate must adopt it, V2 must converge it, Hibernate
 * validation must pass, and the existing row must survive with its JSON intact.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=" + LegacySchemaConvergenceTest.URL
})
class LegacySchemaConvergenceTest {

    static final String URL = "jdbc:h2:mem:legacy-schema;DB_CLOSE_DELAY=-1";
    static final UUID LEGACY_ID = UUID.fromString("6f0f4a1c-8f4c-4a8e-9d1a-1234567890ab");

    @BeforeAll
    static void createLegacySchemaWithData() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE generated_reports (
                        id UUID NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        profession VARCHAR(255) NOT NULL,
                        mode VARCHAR(255) NOT NULL,
                        generated_at TIMESTAMP(6) WITH TIME ZONE,
                        risk_score DOUBLE PRECISION NOT NULL,
                        risk_level VARCHAR(255),
                        payment_status VARCHAR(20) NOT NULL,
                        stripe_session_id VARCHAR(255),
                        expires_at TIMESTAMP(6) WITH TIME ZONE,
                        report_json CLOB,
                        PRIMARY KEY (id)
                    )
                    """);
            statement.execute("""
                    INSERT INTO generated_reports
                        (id, created_at, updated_at, profession, mode, risk_score, risk_level,
                         payment_status, report_json)
                    VALUES ('%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'Accountant', 'profession',
                            5.5, 'Moderate', 'PAID', '{"reportId":"legacy","score":5.5}')
                    """.formatted(LEGACY_ID));
        }
    }

    @Autowired
    private ReportRequestRepository reportRequestRepository;

    @Test
    void legacyRowSurvivesConvergenceWithJsonIntact() {
        ReportRequest legacy = reportRequestRepository.findById(LEGACY_ID).orElseThrow();

        assertEquals("Accountant", legacy.getProfession());
        assertEquals("{\"reportId\":\"legacy\",\"score\":5.5}", legacy.getReportJson());
    }
}
