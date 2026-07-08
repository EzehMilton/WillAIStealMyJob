package com.chikere.jobai.controller;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.GenerateReportResponse;
import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.model.ReportStatusResponse;
import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.PdfService;
import com.chikere.jobai.service.ReportRateLimiterService;
import com.chikere.jobai.service.ReportService;
import io.github.bucket4j.ConsumptionProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportService reportService;
    private final PdfService pdfService;
    private final AnalyticsService analyticsService;
    private final ReportRateLimiterService rateLimiter;
    @Qualifier("pdfTaskExecutor")
    private final Executor pdfTaskExecutor;

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

        log.info("Submitting premium report generation visitorId={}", visitorId);
        try {
            reportService.purgeExpiredUnpaidReports();
            ReportService.SubmittedReport submitted = reportService.startReportGeneration(request, visitorId);
            return ResponseEntity.accepted().body(new GenerateReportResponse(submitted.reportId()));
        } catch (ReportService.GenerationCapacityException e) {
            analyticsService.recordError(visitorId, "report_generation_capacity", null, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "30")
                    .<GenerateReportResponse>build();
        } catch (Exception e) {
            log.error("Report generation submit failed visitorId={}", visitorId, e);
            analyticsService.recordError(visitorId, "report_generation_error", null, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/report/{reportId}/status")
    @ResponseBody
    public ResponseEntity<ReportStatusResponse> reportStatus(@PathVariable String reportId) {
        try {
            return reportService.getGenerationStatus(reportId)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (ReportService.ReportNotFoundException ex) {
            return ResponseEntity.notFound().build();
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
    public CompletableFuture<ResponseEntity<byte[]>> downloadReport(@PathVariable String reportId) {
        Optional<PremiumReport> unlockedReport = reportService.getUnlockedReport(reportId);
        if (unlockedReport.isEmpty()) {
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        try {
            return CompletableFuture.supplyAsync(
                    () -> renderPdfDownload(reportId, unlockedReport.get()),
                    pdfTaskExecutor
            );
        } catch (RuntimeException e) {
            log.warn("PDF generation rejected for reportId={}: {}", reportId, e.getMessage());
            analyticsService.recordError("unknown", "pdf_generation_rejected", reportId, e.getMessage());
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
        }
    }

    private ResponseEntity<byte[]> renderPdfDownload(String reportId, PremiumReport report) {
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
    }
}
