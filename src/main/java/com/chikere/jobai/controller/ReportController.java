package com.chikere.jobai.controller;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.GenerateReportResponse;
import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.PdfService;
import com.chikere.jobai.service.ReportRateLimiterService;
import com.chikere.jobai.service.ReportService;
import io.github.bucket4j.ConsumptionProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportService reportService;
    private final PdfService pdfService;
    private final AnalyticsService analyticsService;
    private final ReportRateLimiterService rateLimiter;

    @PostMapping("/generate-report")
    @ResponseBody
    public ResponseEntity<GenerateReportResponse> generateReport(
            @RequestBody GenerateReportRequest request,
            @RequestHeader(value = "X-Visitor-Id", defaultValue = "unknown") String visitorId) {
        ConsumptionProbe probe = rateLimiter.tryConsume(visitorId);
        if (!probe.isConsumed()) {
            long retryAfter = probe.getNanosToWaitForRefill() / 1_000_000_000;
            log.warn("Rate limit exceeded visitorId={}", visitorId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(retryAfter))
                    .<GenerateReportResponse>build();
        }

        log.info("Generating persisted premium report visitorId={}", visitorId);
        long start = System.nanoTime();
        try {
            reportService.purgeExpiredUnpaidReports();
            ReportService.StoredReport storedReport = reportService.generateAndStoreReport(request);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            analyticsService.recordReportDelivered(visitorId, storedReport.reportId(), request.getProfession(), durationMs);
            analyticsService.recordGenerationCompleted(visitorId, storedReport.reportId(), request.getProfession(), storedReport.report().getGenerationMetrics());
            return ResponseEntity.ok(new GenerateReportResponse(storedReport.reportId()));
        } catch (Exception e) {
            log.error("Report generation failed visitorId={}", visitorId, e);
            analyticsService.recordError(visitorId, "report_generation_error", null, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping({"/report/{reportId}", "/premium-report/{reportId}"})
    public String viewReport(@PathVariable String reportId,
                             @RequestParam(value = "checkout", required = false) String checkoutState,
                             Model model) {
        try {
            reportService.purgeExpiredUnpaidReports();
            if ("success".equals(checkoutState)) {
                reportService.syncPaymentStatusIfNeeded(reportId);
            }
            return reportService.getReportView(reportId)
                    .map(reportView -> {
                        model.addAttribute("report", reportView.report());
                        model.addAttribute("reportId", reportView.reportId());
                        model.addAttribute("reportLocked", reportView.reportLocked());
                        model.addAttribute("paymentStatus", reportView.paymentStatus());
                        model.addAttribute("expiresAt", reportView.expiresAt());
                        model.addAttribute("checkoutState", checkoutState);
                        log.info("{} report page rendered reportId={}",
                                reportView.reportLocked() ? "Locked" : "Unlocked",
                                reportView.reportId());
                        return "premium-report";
                    })
                    .orElse("redirect:/");
        } catch (ReportService.ReportNotFoundException ex) {
            return "redirect:/";
        }
    }

    @GetMapping({"/report/{reportId}/download", "/premium-report/{reportId}/download"})
    @ResponseBody
    public ResponseEntity<byte[]> downloadReport(@PathVariable String reportId) {
        return reportService.getUnlockedReport(reportId)
                .map(report -> {
                    try {
                        byte[] pdf = pdfService.generateReportPdf(report);
                        String filename = "ai-career-report-"
                                + report.getProfession().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                                + ".pdf";
                        return ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                                .contentType(MediaType.APPLICATION_PDF)
                                .body(pdf);
                    } catch (Exception e) {
                        log.error("PDF generation failed for reportId={}", reportId, e);
                        analyticsService.recordError("unknown", "pdf_generation_error", reportId, e.getMessage());
                        return ResponseEntity.internalServerError().<byte[]>build();
                    }
                })
                .orElse(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }
}
