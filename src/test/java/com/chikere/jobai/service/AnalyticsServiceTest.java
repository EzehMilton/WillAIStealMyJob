package com.chikere.jobai.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chikere.jobai.model.AnalyticsEvent;
import com.chikere.jobai.repository.AnalyticsEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    private final AnalyticsEventRepository repository = mock(AnalyticsEventRepository.class);
    private final AnalyticsService analyticsService = new AnalyticsService(repository);
    private ListAppender<ILoggingEvent> appender;
    private Logger analyticsLogger;

    @BeforeEach
    void setUp() {
        analyticsLogger = (Logger) LoggerFactory.getLogger("ANALYTICS");
        appender = new ListAppender<>();
        appender.start();
        analyticsLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        analyticsLogger.detachAppender(appender);
    }

    @Test
    void summaryGeneratedLineIsWellFormed() {
        analyticsService.recordSummaryGenerated("visitor-1", "Nurse", 4.2);

        String line = singleLine();
        assertTrue(line.startsWith("event=summary_generated visitorId=visitor-1 profession=\"Nurse\" riskScore=4.2 ts="));
        assertFalse(line.contains("{}"), "unfilled log placeholder in: " + line);
    }

    @Test
    void everyAnalyticsEventFillsAllItsPlaceholders() {
        analyticsService.recordSummaryGenerated("v", "Nurse", 4.2);
        analyticsService.recordPaymentCompleted("v", "cs_1", 900L, "gbp");
        analyticsService.recordReportDelivered("v", "r1", "Nurse", 1200L);
        analyticsService.recordError("v", "some_error", "r1", "boom");
        analyticsService.record("v", "page_view", "Nurse", 4.2);

        List<ILoggingEvent> events = appender.list;
        assertEquals(5, events.size());
        for (ILoggingEvent event : events) {
            String line = event.getFormattedMessage();
            assertFalse(line.contains("{}"), "unfilled log placeholder in: " + line);
        }
    }

    @Test
    void everyEventIsPersistedToTheAnalyticsTable() {
        analyticsService.recordSummaryGenerated("v", "Nurse", 4.2);
        analyticsService.recordPaymentCompleted("v", "cs_1", 900L, "gbp");
        analyticsService.recordReportDelivered("v", "r1", "Nurse", 1200L);
        analyticsService.recordError("v", "some_error", "r1", "boom");
        analyticsService.record("v", "page_view", "Nurse", 4.2);

        ArgumentCaptor<AnalyticsEvent> captor = ArgumentCaptor.forClass(AnalyticsEvent.class);
        verify(repository, org.mockito.Mockito.times(5)).save(captor.capture());
        List<String> types = captor.getAllValues().stream().map(AnalyticsEvent::getEventType).toList();
        assertEquals(List.of("summary_generated", "payment_completed", "report_delivered", "error", "page_view"), types);
        AnalyticsEvent payment = captor.getAllValues().get(1);
        assertEquals(900L, payment.getAmountCents());
        assertEquals("gbp", payment.getCurrency());
    }

    @Test
    void frontendEventTypeIsSanitizedAndCappedBeforePersisting() {
        analyticsService.record("v", "evil\" riskScore=10 event=payment_completed".repeat(3));

        ArgumentCaptor<AnalyticsEvent> captor = ArgumentCaptor.forClass(AnalyticsEvent.class);
        verify(repository).save(captor.capture());
        String type = captor.getValue().getEventType();
        assertTrue(type.length() <= 40);
        assertTrue(type.matches("[a-zA-Z0-9_.-]+"), "unsafe chars in: " + type);
    }

    @Test
    void persistenceFailureNeverBreaksTheCaller() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        analyticsService.recordPaymentCompleted("v", "cs_1", 900L, "gbp");

        assertEquals(1, appender.list.size());
    }

    private String singleLine() {
        assertEquals(1, appender.list.size());
        return appender.list.get(0).getFormattedMessage();
    }
}
