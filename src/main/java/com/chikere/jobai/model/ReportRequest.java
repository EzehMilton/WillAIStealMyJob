package com.chikere.jobai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "generated_reports", indexes = {
        @Index(name = "idx_reports_stripe_session", columnList = "stripeSessionId"),
        @Index(name = "idx_reports_payment_expiry", columnList = "paymentStatus, expiresAt"),
        @Index(name = "idx_reports_request_hash", columnList = "requestHash")
})
@Getter
@Setter
public class ReportRequest {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Column(nullable = false)
    private String profession;

    @Column(nullable = false)
    private String mode;

    @Column
    private OffsetDateTime generatedAt;

    @Column(nullable = false)
    private Double riskScore;

    @Column
    private String riskLevel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus paymentStatus;

    /** Async generation lifecycle; null on rows created before this column existed. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReportStatus generationStatus;

    private String stripeSessionId;

    /** SHA-256 of the generation inputs, used to dedupe repeated identical requests. */
    @Column(length = 64)
    private String requestHash;

    @Column
    private OffsetDateTime expiresAt;

    /**
     * Serialized PremiumReport (~50 KB typical). Deliberately not @Lob: on PostgreSQL that
     * maps to oid large objects, which can't be queried or dumped as plain text.
     */
    @Column(length = 1_000_000)
    private String reportJson;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = now;
        updatedAt = now;
        if (generatedAt == null) {
            generatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
