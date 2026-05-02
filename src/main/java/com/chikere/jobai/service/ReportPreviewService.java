package com.chikere.jobai.service;

import com.chikere.jobai.model.PremiumReport;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReportPreviewService {

    public PremiumReport buildLockedPreview(PremiumReport report) {
        PremiumReport preview = new PremiumReport();
        preview.setReportId(report.getReportId());
        preview.setProfession(report.getProfession());
        preview.setMode(report.getMode());
        preview.setScore(report.getScore());
        preview.setRiskLevel(report.getRiskLevel());
        preview.setPremiumScore(report.getPremiumScore());
        preview.setPremiumRiskLevel(report.getPremiumRiskLevel());
        preview.setScoreRationale(report.getScoreRationale());
        preview.setGeneratedAt(report.getGeneratedAt());
        preview.setDisruptionWindow(report.getDisruptionWindow());
        preview.setAdaptabilityPotential(report.getAdaptabilityPotential());
        preview.setExecutiveSummary(report.getExecutiveSummary());
        preview.setCoreAdvice(report.getCoreAdvice());
        preview.setTaskExposureMap(firstRows(report.getTaskExposureMap(), 3));
        return preview;
    }

    private <T> List<T> firstRows(List<T> source, int count) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().limit(count).toList();
    }
}
