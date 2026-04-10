package com.chikere.jobai.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ReportStatusResponse {
    private PaymentStatus paymentStatus;
    private ReportStatus reportStatus;
}
