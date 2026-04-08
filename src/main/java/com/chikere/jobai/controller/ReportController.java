package com.chikere.jobai.controller;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.GenerateReportResponse;
import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.PdfService;
import com.chikere.jobai.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportService reportService;
    private final PdfService pdfService;
    private final AnalyticsService analyticsService;

    @PostMapping("/generate-report")
    @ResponseBody
    public ResponseEntity<GenerateReportResponse> generateReport(
            @RequestBody GenerateReportRequest request,
            @RequestHeader(value = "X-Visitor-Id", defaultValue = "unknown") String visitorId) {
        log.info("Generating report for profession: {}", request.getProfession());
        long start = System.nanoTime();
        try {
            PremiumReport report = reportService.generateReport(request);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            analyticsService.recordReportDelivered(visitorId, report.getReportId(), request.getProfession(), durationMs);
            analyticsService.recordGenerationCompleted(visitorId, report.getReportId(), request.getProfession(), report.getGenerationMetrics());
            return ResponseEntity.ok(new GenerateReportResponse(report.getReportId()));
        } catch (Exception e) {
            log.error("Report generation failed for profession={}", request.getProfession(), e);
            analyticsService.recordError(visitorId, "report_generation_error", null, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/premium-report/{reportId}")
    public String viewReport(@PathVariable String reportId, Model model) {
        return reportService.getReport(reportId)
                .map(report -> {
                    model.addAttribute("report", report);
                    return "premium-report";
                })
                .orElse("redirect:/");
    }

    @GetMapping("/premium-report/{reportId}/download")
    @ResponseBody
    public ResponseEntity<byte[]> downloadReport(@PathVariable String reportId) {
        return reportService.getReport(reportId)
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
                .orElse(ResponseEntity.notFound().build());
    }
}
