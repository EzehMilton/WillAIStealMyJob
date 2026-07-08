package com.chikere.jobai.repository;

import com.chikere.jobai.model.AnalyticsEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {
    long countByEventTypeAndOccurredAtAfter(String eventType, OffsetDateTime after);

    List<AnalyticsEvent> findByEventTypeAndOccurredAtAfterOrderByOccurredAtDesc(String eventType, OffsetDateTime after);
}
