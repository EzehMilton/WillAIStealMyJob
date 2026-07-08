package com.chikere.jobai.controller;

import com.chikere.jobai.model.AssessmentProcessingResult;
import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskAssessmentForm;
import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.JourneyConfigRegistry;
import com.chikere.jobai.service.RiskAssessmentService;
import com.chikere.jobai.service.RiskAssessmentService.DocumentParseException;
import com.chikere.jobai.service.RiskAssessmentService.ValidationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@Slf4j
public class RiskAssessorController {

    private final RiskAssessmentService riskAssessmentService;
    private final AnalyticsService analyticsService;
    private final JourneyConfigRegistry journeyConfigRegistry;

    @GetMapping("/")
    public String home(Model model) {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode(journeyConfigRegistry.get(JourneyType.PROFESSIONAL).legacyModeValue());
        model.addAttribute("riskAssessmentForm", form);
        addWordLimits(model);
        return "index";
    }

    @PostMapping("/assess")
    public String assessRisk(@Valid @ModelAttribute("riskAssessmentForm") RiskAssessmentForm form,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirectAttributes,
                             @CookieValue(value = "visitor_id", defaultValue = "unknown") String visitorId) {

        if (bindingResult.hasErrors()) {
            addWordLimits(model);
            return "index";
        }

        if ("manual".equals(form.getInputMethod())) {
            int limit = journeyConfigRegistry.get(form.getMode()).wordLimit();
            int wordCount = countWords(form.getRoleSummary());
            if (wordCount > limit) {
                bindingResult.rejectValue("roleSummary", "error.roleSummary",
                        "Please keep your description under " + limit + " words (" + wordCount + " used).");
                addWordLimits(model);
                return "index";
            }
        }

        try {
            AssessmentProcessingResult processingResult = riskAssessmentService.processAssessmentWithDetails(form);
            JobRiskAssessment assessment = processingResult.assessment();
            analyticsService.recordSummaryGenerated(visitorId, form.getProfession(), assessment.getScore());
            analyticsService.recordGenerationCompleted(visitorId, null, form.getProfession(), assessment.getGenerationMetrics());
            addSuccessAttributes(redirectAttributes, form, processingResult);

        } catch (ValidationException e) {
            bindingResult.rejectValue(e.getField(), "error." + e.getField(), e.getMessage());
            analyticsService.recordError(visitorId, "validation_error", null, e.getMessage());
            addWordLimits(model);
            return "index";

        } catch (DocumentParseException e) {
            bindingResult.rejectValue("cvFile", "error.cvFile", e.getMessage());
            analyticsService.recordError(visitorId, "document_parse_error", null, e.getMessage());
            addWordLimits(model);
            return "index";

        } catch (Exception e) {
            log.error("Error assessing job risk", e);
            analyticsService.recordError(visitorId, "summary_error", null, e.getMessage());
            redirectAttributes.addFlashAttribute("error",
                    "An error occurred while assessing the risk. Please try again.");
            redirectAttributes.addFlashAttribute("success", false);
        }

        return "redirect:/result";
    }

    private void addWordLimits(Model model) {
        model.addAttribute("wordLimitProfession", journeyConfigRegistry.get(JourneyType.PROFESSIONAL).wordLimit());
        model.addAttribute("wordLimitCourse", journeyConfigRegistry.get(JourneyType.UNIVERSITY_STUDENT).wordLimit());
    }

    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    @GetMapping("/result")
    public String result(Model model) {
        // The page renders entirely from flash attributes; on a refresh or direct
        // visit they're gone and the page would be an empty shell — go home instead.
        if (!model.containsAttribute("success")) {
            return "redirect:/";
        }
        return "result";
    }

    @GetMapping("/sample-report")
    public String sampleReport() {
        return "sample-report";
    }

    @GetMapping("/generating-report")
    public String generatingReport() {
        return "generating-report";
    }

    private void addSuccessAttributes(RedirectAttributes redirectAttributes,
                                      RiskAssessmentForm form,
                                      AssessmentProcessingResult processingResult) {
        JobRiskAssessment assessment = processingResult.assessment();
        redirectAttributes.addFlashAttribute("mode", form.getMode());
        redirectAttributes.addFlashAttribute("profession", form.getProfession());
        redirectAttributes.addFlashAttribute("score", assessment.getScore());
        redirectAttributes.addFlashAttribute("riskLevel", assessment.getRiskLevel());
        redirectAttributes.addFlashAttribute("summary", assessment.getSummary());
        redirectAttributes.addFlashAttribute("assessment", assessment.getAssessment());
        redirectAttributes.addFlashAttribute("originalDetails", processingResult.resolvedDetails());
        redirectAttributes.addFlashAttribute("success", true);
    }
}
