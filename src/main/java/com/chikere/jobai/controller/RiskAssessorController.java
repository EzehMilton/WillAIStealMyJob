package com.chikere.jobai.controller;

import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.RiskAssessmentForm;
import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.RiskAssessmentService;
import com.chikere.jobai.service.RiskAssessmentService.DocumentParseException;
import com.chikere.jobai.service.RiskAssessmentService.ValidationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.form.role-summary-word-limit:750}")
    private int roleSummaryWordLimit;

    @GetMapping("/")
    public String home(Model model) {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        model.addAttribute("riskAssessmentForm", form);
        model.addAttribute("wordLimit", roleSummaryWordLimit);
        return "index";
    }

    @PostMapping("/assess")
    public String assessRisk(@Valid @ModelAttribute("riskAssessmentForm") RiskAssessmentForm form,
                             BindingResult bindingResult,
                             Model model,
                             RedirectAttributes redirectAttributes,
                             @CookieValue(value = "visitor_id", defaultValue = "unknown") String visitorId) {

        if (bindingResult.hasErrors()) {
            model.addAttribute("wordLimit", roleSummaryWordLimit);
            return "index";
        }

        if ("manual".equals(form.getInputMethod())) {
            int wordCount = countWords(form.getRoleSummary());
            if (wordCount > roleSummaryWordLimit) {
                bindingResult.rejectValue("roleSummary", "error.roleSummary",
                        "Please keep your description under " + roleSummaryWordLimit + " words (" + wordCount + " used).");
                model.addAttribute("wordLimit", roleSummaryWordLimit);
                return "index";
            }
        }

        try {
            JobRiskAssessment assessment = riskAssessmentService.processAssessment(form);
            analyticsService.record(visitorId, "summary_generated", form.getProfession(), assessment.getScore());
            addSuccessAttributes(redirectAttributes, form, assessment);

        } catch (ValidationException e) {
            bindingResult.rejectValue(e.getField(), "error." + e.getField(), e.getMessage());
            model.addAttribute("wordLimit", roleSummaryWordLimit);
            return "index";

        } catch (DocumentParseException e) {
            bindingResult.rejectValue("cvFile", "error.cvFile", e.getMessage());
            model.addAttribute("wordLimit", roleSummaryWordLimit);
            return "index";

        } catch (Exception e) {
            log.error("Error assessing job risk", e);
            redirectAttributes.addFlashAttribute("error",
                    "An error occurred while assessing the risk. Please try again.");
            redirectAttributes.addFlashAttribute("success", false);
        }

        return "redirect:/result";
    }

    private int countWords(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    @GetMapping("/result")
    public String result(Model model) {
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
                                      JobRiskAssessment assessment) {
        redirectAttributes.addFlashAttribute("mode", form.getMode());
        redirectAttributes.addFlashAttribute("profession", form.getProfession());
        redirectAttributes.addFlashAttribute("score", assessment.getScore());
        redirectAttributes.addFlashAttribute("riskLevel", assessment.getRiskLevel());
        redirectAttributes.addFlashAttribute("summary", assessment.getSummary());
        redirectAttributes.addFlashAttribute("assessment", assessment.getAssessment());
        redirectAttributes.addFlashAttribute("success", true);
    }
}