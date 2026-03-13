package com.chikere.jobai.controller;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.GenerateReportResponse;
import com.chikere.jobai.model.PremiumReport;
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

@Controller
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportService reportService;
    private final PdfService pdfService;

    @PostMapping("/generate-report")
    @ResponseBody
    public ResponseEntity<GenerateReportResponse> generateReport(@RequestBody GenerateReportRequest request) {
        log.info("Generating report for profession: {}", request.getProfession());
        PremiumReport report = reportService.generateReport(request);
        return ResponseEntity.ok(new GenerateReportResponse(report.getReportId()));
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
                        return ResponseEntity.internalServerError().<byte[]>build();
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }
}