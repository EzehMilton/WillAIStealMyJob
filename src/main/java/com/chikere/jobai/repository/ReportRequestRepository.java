package com.chikere.jobai.repository;

import com.chikere.jobai.model.ReportRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReportRequestRepository extends JpaRepository<ReportRequest, UUID> {
    Optional<ReportRequest> findByStripeSessionId(String stripeSessionId);

    void deleteByPaymentStatusNotAndExpiresAtBefore(com.chikere.jobai.model.PaymentStatus paymentStatus,
                                                    java.time.OffsetDateTime expiresAt);
}
