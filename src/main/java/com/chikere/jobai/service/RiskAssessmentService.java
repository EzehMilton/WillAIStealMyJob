package com.chikere.jobai.service;

import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.RiskAssessmentForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskAssessmentService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int MIN_EXTRACTED_TEXT_LENGTH = 50;

    private final JobAiService jobAiService;
    private final DocumentParserService documentParserService;

    /**
     * Process the risk assessment form and return the assessment result.
     *
     * @param form the submitted form
     * @return the job risk assessment
     * @throws ValidationException if validation fails
     * @throws DocumentParseException if CV parsing fails
     */
    public JobRiskAssessment processAssessment(RiskAssessmentForm form) {
        String roleSummary;
        try {
            roleSummary = extractRoleSummary(form);
        } catch (ValidationException | DocumentParseException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isCvUploadMode(form)) {
                log.warn("Unexpected error while processing CV input: {}", e.getMessage());
                throw new DocumentParseException(
                        "Failed to read CV. Please try a different file or enter details manually.",
                        e
                );
            }
            throw e;
        }
        
        log.info("Processing risk assessment for mode: {}, profession: {}", 
                form.getMode(), form.getProfession());
        
        return jobAiService.assessJobRisk(
                form.getMode(),
                form.getProfession(),
                roleSummary
        );
    }

    private String extractRoleSummary(RiskAssessmentForm form) {
        if (isCvUploadMode(form)) {
            return extractFromCv(form.getCvFile());
        } else {
            return extractFromManualInput(form.getRoleSummary());
        }
    }

    private boolean isCvUploadMode(RiskAssessmentForm form) {
        return "cv".equals(form.getInputMethod()) && "profession".equals(form.getMode());
    }

    private String extractFromCv(MultipartFile cvFile) {
        validateCvFile(cvFile);
        
        try {
            String extractedText = documentParserService.extractText(cvFile);
            log.info("Extracted CV text, length: {} characters", extractedText.length());
            
            if (extractedText.length() < MIN_EXTRACTED_TEXT_LENGTH) {
                throw new ValidationException("cvFile", 
                        "Could not extract enough text from your CV. Please try a different file or enter details manually.");
            }
            
            return extractedText;
            
        } catch (ValidationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            log.error("Invalid file: {}", e.getMessage());
            throw new ValidationException("cvFile", e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to parse CV: {}", e.getMessage());
            throw new DocumentParseException("Failed to read CV. Please try a different file or enter details manually.", e);
        }
    }

    private String extractFromManualInput(String roleSummary) {
        if (roleSummary == null || roleSummary.isBlank()) {
            throw new ValidationException("roleSummary", "Please describe your role or upload a CV");
        }
        return roleSummary;
    }

    private void validateCvFile(MultipartFile cvFile) {
        if (cvFile == null || cvFile.isEmpty()) {
            throw new ValidationException("cvFile", "Please upload your CV");
        }

        if (cvFile.getSize() > MAX_FILE_SIZE) {
            throw new ValidationException("cvFile", "File size must be less than 5MB");
        }

        String filename = cvFile.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new ValidationException("cvFile", "Please upload a PDF file only");
        }
    }

    /**
     * Exception thrown when form validation fails.
     */
    public static class ValidationException extends RuntimeException {
        private final String field;

        public ValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String getField() {
            return field;
        }
    }

    /**
     * Exception thrown when document parsing fails.
     */
    public static class DocumentParseException extends RuntimeException {
        public DocumentParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
