package com.chikere.jobai.service;

import com.chikere.jobai.model.AssessmentProcessingResult;
import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskAssessmentForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiskAssessmentServiceTest {

    @Mock
    private JobAiService jobAiService;

    @Mock
    private DocumentParserService documentParserService;

    private RiskAssessmentService riskAssessmentService;

    @BeforeEach
    void setUp() {
        riskAssessmentService = new RiskAssessmentService(
                jobAiService,
                documentParserService,
                new JourneyConfigRegistry(800, 450, 350)
        );
    }

    @Test
    void processAssessment_manualInput_callsJobAiService() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setRoleSummary("Builds systems");
        form.setInputMethod("manual");

        JobRiskAssessment expected = new JobRiskAssessment();
        when(jobAiService.assessJobRisk("profession", "Engineer", "Builds systems")).thenReturn(expected);

        JobRiskAssessment result = riskAssessmentService.processAssessment(form);

        assertSame(expected, result);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessmentWithDetails_preservesManualProfessionalDetails() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setRoleSummary("Builds safety-critical systems");
        form.setInputMethod("manual");

        JobRiskAssessment expected = new JobRiskAssessment();
        when(jobAiService.assessJobRisk("profession", "Engineer", "Builds safety-critical systems"))
                .thenReturn(expected);

        AssessmentProcessingResult result = riskAssessmentService.processAssessmentWithDetails(form);

        assertSame(expected, result.assessment());
        assertEquals("Builds safety-critical systems", result.resolvedDetails());
        assertEquals("Engineer", result.subject());
        assertEquals(JourneyType.PROFESSIONAL, result.journeyType());
        assertEquals("profession", result.mode());
    }

    @Test
    void processAssessmentWithDetails_preservesCourseDetails() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("course");
        form.setProfession("Computer Science");
        form.setRoleSummary("I want to work in software or product engineering");
        form.setInputMethod("manual");

        JobRiskAssessment expected = new JobRiskAssessment();
        when(jobAiService.assessJobRisk("course", "Computer Science", "I want to work in software or product engineering"))
                .thenReturn(expected);

        AssessmentProcessingResult result = riskAssessmentService.processAssessmentWithDetails(form);

        assertSame(expected, result.assessment());
        assertEquals("I want to work in software or product engineering", result.resolvedDetails());
        assertEquals(JourneyType.UNIVERSITY_STUDENT, result.journeyType());
    }

    @Test
    void processAssessmentWithDetails_preservesALevelDetails() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("a_level");
        form.setProfession("Maths, Psychology, Business");
        form.setRoleSummary("I enjoy problem solving, people, and business ideas");
        form.setInputMethod("manual");

        JobRiskAssessment expected = new JobRiskAssessment();
        when(jobAiService.assessJobRisk("a_level", "Maths, Psychology, Business", "I enjoy problem solving, people, and business ideas"))
                .thenReturn(expected);

        AssessmentProcessingResult result = riskAssessmentService.processAssessmentWithDetails(form);

        assertSame(expected, result.assessment());
        assertEquals("I enjoy problem solving, people, and business ideas", result.resolvedDetails());
        assertEquals(JourneyType.A_LEVEL_UNDECIDED, result.journeyType());
    }

    @Test
    void processAssessment_cvMode_extractsTextAndCallsJobAiService() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Designer");
        form.setInputMethod("cv");
        MockMultipartFile cvFile = new MockMultipartFile(
                "cvFile",
                "resume.pdf",
                "application/pdf",
                "pdf".getBytes()
        );
        form.setCvFile(cvFile);

        String extracted = "x".repeat(60);
        JobRiskAssessment expected = new JobRiskAssessment();

        when(documentParserService.extractText(cvFile)).thenReturn(extracted);
        when(jobAiService.assessJobRisk("profession", "Designer", extracted)).thenReturn(expected);

        JobRiskAssessment result = riskAssessmentService.processAssessment(form);

        assertSame(expected, result);
        verify(documentParserService).extractText(cvFile);
    }

    @Test
    void processAssessmentWithDetails_usesExtractedCvTextAsResolvedDetails() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Designer");
        form.setInputMethod("cv");
        MockMultipartFile cvFile = new MockMultipartFile(
                "cvFile",
                "resume.pdf",
                "application/pdf",
                "pdf".getBytes()
        );
        form.setCvFile(cvFile);

        String extracted = "Extracted CV text ".repeat(5);
        JobRiskAssessment expected = new JobRiskAssessment();

        when(documentParserService.extractText(cvFile)).thenReturn(extracted);
        when(jobAiService.assessJobRisk("profession", "Designer", extracted)).thenReturn(expected);

        AssessmentProcessingResult result = riskAssessmentService.processAssessmentWithDetails(form);

        assertSame(expected, result.assessment());
        assertEquals(extracted, result.resolvedDetails());
        verify(documentParserService).extractText(cvFile);
    }

    @Test
    void processAssessment_courseModeIgnoresCvInput() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("course");
        form.setProfession("Computer Science");
        form.setRoleSummary("Research and development");
        form.setInputMethod("cv");
        form.setCvFile(new MockMultipartFile("cvFile", "resume.pdf", "application/pdf", "pdf".getBytes()));

        JobRiskAssessment expected = new JobRiskAssessment();
        when(jobAiService.assessJobRisk("course", "Computer Science", "Research and development"))
                .thenReturn(expected);

        JobRiskAssessment result = riskAssessmentService.processAssessment(form);

        assertSame(expected, result);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessment_aLevelMode_acceptsManualInput() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("a_level");
        form.setProfession("Maths, Psychology, Business");
        form.setRoleSummary("I like problem solving and helping people");
        form.setInputMethod("manual");

        JobRiskAssessment expected = new JobRiskAssessment();
        when(jobAiService.assessJobRisk("a_level", "Maths, Psychology, Business", "I like problem solving and helping people"))
                .thenReturn(expected);

        JobRiskAssessment result = riskAssessmentService.processAssessment(form);

        assertSame(expected, result);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessment_aLevelModeRequiresManualDetails() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("a_level");
        form.setProfession("Maths and Biology");
        form.setInputMethod("manual");
        form.setRoleSummary(" ");

        RiskAssessmentService.ValidationException exception = assertThrows(
                RiskAssessmentService.ValidationException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        assertEquals("roleSummary", exception.getField());
        assertTrue(exception.getMessage().contains("interests, strengths, subject ideas, or future preferences"));
        verifyNoInteractions(jobAiService);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessment_aLevelModeIgnoresCvInput() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("a_level");
        form.setProfession("Maths and Computer Science");
        form.setRoleSummary("I enjoy logic and creative projects");
        form.setInputMethod("cv");
        form.setCvFile(new MockMultipartFile("cvFile", "resume.pdf", "application/pdf", "pdf".getBytes()));

        JobRiskAssessment expected = new JobRiskAssessment();
        when(jobAiService.assessJobRisk("a_level", "Maths and Computer Science", "I enjoy logic and creative projects"))
                .thenReturn(expected);

        JobRiskAssessment result = riskAssessmentService.processAssessment(form);

        assertSame(expected, result);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessment_requiresSubjectField() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("a_level");
        form.setProfession(" ");
        form.setRoleSummary("I enjoy science");
        form.setInputMethod("manual");

        RiskAssessmentService.ValidationException exception = assertThrows(
                RiskAssessmentService.ValidationException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        assertEquals("profession", exception.getField());
        assertTrue(exception.getMessage().contains("subjects you are considering or interested in"));
        verifyNoInteractions(jobAiService);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessment_manualInput_requiresRoleSummary() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setInputMethod("manual");
        form.setRoleSummary(" ");

        RiskAssessmentService.ValidationException exception = assertThrows(
                RiskAssessmentService.ValidationException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        assertEquals("roleSummary", exception.getField());
        verifyNoInteractions(jobAiService);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessment_cvMode_requiresFile() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setInputMethod("cv");

        RiskAssessmentService.ValidationException exception = assertThrows(
                RiskAssessmentService.ValidationException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        assertEquals("cvFile", exception.getField());
        verifyNoInteractions(jobAiService);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessment_cvMode_rejectsLargeFiles() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setInputMethod("cv");
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        form.setCvFile(new MockMultipartFile("cvFile", "resume.pdf", "application/pdf", tooLarge));

        RiskAssessmentService.ValidationException exception = assertThrows(
                RiskAssessmentService.ValidationException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        assertEquals("cvFile", exception.getField());
        assertTrue(exception.getMessage().contains("File size must be less than 2MB"));
        verifyNoInteractions(jobAiService);
        verifyNoInteractions(documentParserService);
    }

    @Test
    void processAssessment_cvMode_rejectsInvalidExtension() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setInputMethod("cv");
        form.setCvFile(new MockMultipartFile("cvFile", "resume.txt", "text/plain", "txt".getBytes()));

        RiskAssessmentService.ValidationException exception = assertThrows(
                RiskAssessmentService.ValidationException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        assertEquals("cvFile", exception.getField());
        assertTrue(exception.getMessage().contains("Please upload a PDF, DOC, or DOCX file"));
        verifyNoInteractions(jobAiService);
        verifyNoInteractions(documentParserService);
    }

    @ParameterizedTest
    @CsvSource({
            "resume.doc,application/msword",
            "resume.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    })
    void processAssessment_cvMode_acceptsWordDocuments(String filename, String contentType) {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setInputMethod("cv");
        MockMultipartFile cvFile = new MockMultipartFile("cvFile", filename, contentType, "doc".getBytes());
        form.setCvFile(cvFile);

        String extracted = "x".repeat(60);
        JobRiskAssessment expected = new JobRiskAssessment();

        when(documentParserService.extractText(cvFile)).thenReturn(extracted);
        when(jobAiService.assessJobRisk("profession", "Engineer", extracted)).thenReturn(expected);

        JobRiskAssessment result = riskAssessmentService.processAssessment(form);

        assertSame(expected, result);
        verify(documentParserService).extractText(cvFile);
    }

    @Test
    void processAssessment_cvMode_requiresSufficientText() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setInputMethod("cv");
        MockMultipartFile cvFile = new MockMultipartFile("cvFile", "resume.pdf", "application/pdf", "pdf".getBytes());
        form.setCvFile(cvFile);

        when(documentParserService.extractText(cvFile)).thenReturn("short text");

        RiskAssessmentService.ValidationException exception = assertThrows(
                RiskAssessmentService.ValidationException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        assertEquals("cvFile", exception.getField());
        assertTrue(exception.getMessage().contains("Could not extract enough text"));
        verify(jobAiService, never()).assessJobRisk("profession", "Engineer", "short text");
    }

    @Test
    void processAssessment_cvMode_mapsIllegalArgumentException() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setInputMethod("cv");
        MockMultipartFile cvFile = new MockMultipartFile("cvFile", "resume.pdf", "application/pdf", "pdf".getBytes());
        form.setCvFile(cvFile);

        when(documentParserService.extractText(cvFile)).thenThrow(new IllegalArgumentException("Bad file"));

        RiskAssessmentService.ValidationException exception = assertThrows(
                RiskAssessmentService.ValidationException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        assertEquals("cvFile", exception.getField());
        assertTrue(exception.getMessage().contains("Bad file"));
        verifyNoInteractions(jobAiService);
    }

    @Test
    void processAssessment_cvMode_mapsUnexpectedException() {
        RiskAssessmentForm form = new RiskAssessmentForm();
        form.setMode("profession");
        form.setProfession("Engineer");
        form.setInputMethod("cv");
        MockMultipartFile cvFile = new MockMultipartFile("cvFile", "resume.pdf", "application/pdf", "pdf".getBytes());
        form.setCvFile(cvFile);

        when(documentParserService.extractText(cvFile)).thenThrow(new RuntimeException("boom"));

        assertThrows(
                RiskAssessmentService.DocumentParseException.class,
                () -> riskAssessmentService.processAssessment(form)
        );

        verifyNoInteractions(jobAiService);
    }
}
