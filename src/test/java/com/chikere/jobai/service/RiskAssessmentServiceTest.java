package com.chikere.jobai.service;

import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.RiskAssessmentForm;
import org.hibernate.validator.internal.IgnoreForbiddenApisErrors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @InjectMocks
    private RiskAssessmentService riskAssessmentService;

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
    @Disabled
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
