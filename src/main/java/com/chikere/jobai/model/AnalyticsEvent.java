package com.chikere.jobai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One funnel/revenue event. The queryable source of truth for product analytics —
 * the ANALYTICS log lines are only a tail-time convenience.
 */
@Entity
@Table(name = "analytics_events", indexes = {
        @Index(name = "idx_analytics_type_time", columnList = "eventType, occurredAt")
})
@Getter
@Setter
public class AnalyticsEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private OffsetDateTime occurredAt;

    @Column(nullable = false, length = 40)
    private String eventType;

    @Column(length = 100)
    private String visitorId;

    @Column(length = 40)
    private String reportId;

    @Column(length = 300)
    private String profession;

    private Double riskScore;

    private Long amountCents;

    @Column(length = 10)
    private String currency;

    private Long durationMs;

    @Column(length = 500)
    private String detail;
}
