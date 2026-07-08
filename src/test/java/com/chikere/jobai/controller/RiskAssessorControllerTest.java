package com.chikere.jobai.controller;

import com.chikere.jobai.model.AssessmentProcessingResult;
import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskAssessmentForm;
import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.JourneyConfigRegistry;
import com.chikere.jobai.service.RiskAssessmentService;
import com.chikere.jobai.service.RiskAssessmentService.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskAssessorControllerTest {

    private RiskAssessmentService riskAssessmentService;
    private RiskAssessorController controller;

    @BeforeEach
    void setUp() {
        riskAssessmentService = mock(RiskAssessmentService.class);
        controller = new RiskAssessorController(
                riskAssessmentService,
                mock(AnalyticsService.class),
                new JourneyConfigRegistry(800, 450, 350)
        );
    }

    @Test
    void directVisitToResultWithoutFlashAttributesRedirectsHome() {
        String view = controller.result(new ExtendedModelMap());

        assertEquals("redirect:/", view);
    }

    @Test
    void resultRendersWhenFlashAttributesArePresent() {
        ExtendedModelMap model = new ExtendedModelMap();
        model.addAttribute("success", true);
        model.addAttribute("score", 5.5);

        String view = controller.result(model);

        assertEquals("result", view);
    }

    @Test
    void resultRendersErrorStateFlashedByFailedAssessment() {
        ExtendedModelMap model = new ExtendedModelMap();
        model.addAttribute("success", false);
        model.addAttribute("error", "An error occurred while assessing the risk. Please try again.");

        String view = controller.result(model);

        assertEquals("result", view);
    }

    @Test
    void assessRisk_preservesManualProfessionalDetailsInResultFlashModel() {
        assertOriginalDetailsFlashed(
                form("profession", "Engineer", "I design and maintain production systems", "manual"),
                JourneyType.PROFESSIONAL,
                "I design and maintain production systems"
        );
    }

    @Test
    void assessRisk_preservesCourseDetailsInResultFlashModel() {
        assertOriginalDetailsFlashed(
                form("course", "Computer Science", "I want a software engineering or AI product career", "manual"),
                JourneyType.UNIVERSITY_STUDENT,
                "I want a software engineering or AI product career"
        );
    }

    @Test
    void assessRisk_preservesALevelDetailsInResultFlashModel() {
        assertOriginalDetailsFlashed(
                form("a_level", "Maths, Psychology, Business", "I like problem solving, people, and starting projects", "manual"),
                JourneyType.A_LEVEL_UNDECIDED,
                "I like problem solving, people, and starting projects"
        );
    }

    @Test
    void assessRisk_preservesExtractedCvDetailsInResultFlashModel() {
        assertOriginalDetailsFlashed(
                form("profession", "Designer", "", "cv"),
                JourneyType.PROFESSIONAL,
                "Extracted CV details with projects, tools, achievements, and responsibilities."
        );
    }

    private void assertOriginalDetailsFlashed(RiskAssessmentForm form,
                                              JourneyType journeyType,
                                              String resolvedDetails) {
        JobRiskAssessment assessment = assessment();
        when(riskAssessmentService.processAssessmentWithDetails(form))
                .thenReturn(new AssessmentProcessingResult(
                        assessment,
                        resolvedDetails,
                        form.getProfession(),
                        journeyType,
                        form.getMode()
                ));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.assessRisk(
                form,
                new BeanPropertyBindingResult(form, "riskAssessmentForm"),
                new ExtendedModelMap(),
                redirectAttributes,
                "visitor-123"
        );

        Map<String, ?> flash = redirectAttributes.getFlashAttributes();
        assertEquals("redirect:/result", view);
        assertEquals(resolvedDetails, flash.get("originalDetails"));
        assertEquals(form.getMode(), flash.get("mode"));
        assertEquals(form.getProfession(), flash.get("profession"));
        assertEquals(4.0, flash.get("score"));
        assertEquals("Moderate", flash.get("riskLevel"));
        assertEquals("Summary", flash.get("summary"));
        assertEquals("Assessment", flash.get("assessment"));
        assertEquals(true, flash.get("success"));
    }

    @Test
    void assessRisk_validationExceptionReturnsToIndex() {
        RiskAssessmentForm form = form("a_level", "Maths", " ", "manual");
        when(riskAssessmentService.processAssessmentWithDetails(form))
                .thenThrow(new ValidationException("roleSummary", "Tell us about your interests"));

        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(form, "riskAssessmentForm");
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.assessRisk(
                form,
                bindingResult,
                model,
                new RedirectAttributesModelMap(),
                "visitor-123"
        );

        assertEquals("index", view);
        assertTrue(bindingResult.hasFieldErrors("roleSummary"));
        assertEquals(800, model.get("wordLimitProfession"));
        assertEquals(450, model.get("wordLimitCourse"));
    }

    private RiskAssessmentForm form(String mode, String profession, String roleSummary, String inputMethod) {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode(mode);
        form.setProfession(profession);
        form.setRoleSummary(roleSummary);
        form.setInputMethod(inputMethod);
        return form;
    }

    private JobRiskAssessment assessment() {
        JobRiskAssessment assessment = new JobRiskAssessment();
        assessment.setScore(4.0);
        assessment.setRiskLevel("Moderate");
        assessment.setSummary("Summary");
        assessment.setAssessment("Assessment");
        return assessment;
    }
}
