package com.chikere.jobai.controller;

import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.RiskAssessmentForm;
import com.chikere.jobai.service.RiskAssessmentService;
import com.chikere.jobai.service.RiskAssessmentService.DocumentParseException;
import com.chikere.jobai.service.RiskAssessmentService.ValidationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@Slf4j
public class RiskAssessorController {

    private final RiskAssessmentService riskAssessmentService;

    @GetMapping("/")
    public String home(Model model) {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        model.addAttribute("riskAssessmentForm", form);
        return "index";
    }

    @PostMapping("/assess")
    public String assessRisk(@Valid @ModelAttribute("riskAssessmentForm") RiskAssessmentForm form,
                             BindingResult bindingResult,
                             RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return "index";
        }

        try {
            JobRiskAssessment assessment = riskAssessmentService.processAssessment(form);
            addSuccessAttributes(redirectAttributes, form, assessment);

        } catch (ValidationException e) {
            bindingResult.rejectValue(e.getField(), "error." + e.getField(), e.getMessage());
            return "index";

        } catch (DocumentParseException e) {
            bindingResult.rejectValue("cvFile", "error.cvFile", e.getMessage());
            return "index";

        } catch (Exception e) {
            log.error("Error assessing job risk", e);
            redirectAttributes.addFlashAttribute("error",
                    "An error occurred while assessing the risk. Please try again.");
            redirectAttributes.addFlashAttribute("success", false);
        }

        return "redirect:/result";
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