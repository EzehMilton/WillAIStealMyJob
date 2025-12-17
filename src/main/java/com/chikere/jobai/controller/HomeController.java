package com.chikere.jobai.controller;

import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.RiskAssessmentForm;
import com.chikere.jobai.service.JobAiService;
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
public class HomeController {

    private final JobAiService jobAiService;

    @GetMapping("/")
    public String home(Model model) {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession"); // Set default to profession mode
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

        log.info("Processing risk assessment for mode: {}, profession: {}", form.getMode(), form.getProfession());

        try {
            // Call the AI service to assess job risk
            JobRiskAssessment jobRiskAssessment = jobAiService.assessJobRisk(
                    form.getMode(),
                    form.getProfession(),
                    form.getRoleSummary()
            );

            // Add data to redirect attributes
            redirectAttributes.addFlashAttribute("mode", form.getMode());
            redirectAttributes.addFlashAttribute("profession", form.getProfession());
            redirectAttributes.addFlashAttribute("score", jobRiskAssessment.getScore());
            redirectAttributes.addFlashAttribute("riskLevel", jobRiskAssessment.getRiskLevel());
            redirectAttributes.addFlashAttribute("summary", jobRiskAssessment.getSummary());
            redirectAttributes.addFlashAttribute("assessment", jobRiskAssessment.getAssessment());
            redirectAttributes.addFlashAttribute("success", true);

        } catch (Exception e) {
            log.error("Error assessing job risk", e);
            redirectAttributes.addFlashAttribute("error", "An error occurred while assessing the risk. Please try again.");
            redirectAttributes.addFlashAttribute("success", false);
        }

        return "redirect:/result";
    }

    @GetMapping("/result")
    public String result(Model model) {
        // The model will contain flash attributes from the POST request
        return "result";
    }
}