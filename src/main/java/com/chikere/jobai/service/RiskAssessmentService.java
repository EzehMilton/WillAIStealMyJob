package com.chikere.jobai.service;

import com.chikere.jobai.model.AssessmentProcessingResult;
import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.JobRiskAssessment;
import com.chikere.jobai.model.RiskAssessmentForm;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class RiskAssessmentService {

    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // 2MB
    private static final int MIN_EXTRACTED_TEXT_LENGTH = 50;
    private static final Set<String> SUPPORTED_CV_EXTENSIONS = Set.of(".pdf", ".doc", ".docx");

    private final JobAiService jobAiService;
    private final DocumentParserService documentParserService;
    private final JourneyConfigRegistry journeyConfigRegistry;

    /**
     * Process the risk assessment form and return the assessment result.
     *
     * @param form the submitted form
     * @return the job risk assessment
     * @throws ValidationException if validation fails
     * @throws DocumentParseException if CV parsing fails
     */
    public JobRiskAssessment processAssessment(RiskAssessmentForm form) {
        return processAssessmentWithDetails(form).assessment();
    }

    public AssessmentProcessingResult processAssessmentWithDetails(RiskAssessmentForm form) {
        validateSubject(form);

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

        JobRiskAssessment assessment = jobAiService.assessJobRisk(
                form.getMode(),
                form.getProfession(),
                roleSummary
        );

        JourneyType journeyType = journeyConfigRegistry.get(form.getMode()).journeyType();
        return new AssessmentProcessingResult(
                assessment,
                roleSummary,
                form.getProfession(),
                journeyType,
                form.getMode()
        );
    }

    private String extractRoleSummary(RiskAssessmentForm form) {
        if (isCvUploadMode(form)) {
            return extractFromCv(form.getCvFile());
        } else {
            return extractFromManualInput(form);
        }
    }

    private boolean isCvUploadMode(RiskAssessmentForm form) {
        return "cv".equals(form.getInputMethod()) && journeyConfigRegistry.get(form.getMode()).cvUploadAllowed();
    }

    private void validateSubject(RiskAssessmentForm form) {
        if (form.getProfession() == null || form.getProfession().isBlank()) {
            JourneyType journeyType = journeyConfigRegistry.get(form.getMode()).journeyType();
            throw new ValidationException("profession", subjectRequiredMessage(journeyType));
        }
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

    private String extractFromManualInput(RiskAssessmentForm form) {
        String roleSummary = form.getRoleSummary();
        if (roleSummary == null || roleSummary.isBlank()) {
            JourneyType journeyType = journeyConfigRegistry.get(form.getMode()).journeyType();
            throw new ValidationException("roleSummary", detailsRequiredMessage(journeyType));
        }
        return roleSummary;
    }

    private String subjectRequiredMessage(JourneyType journeyType) {
        if (journeyType == JourneyType.UNIVERSITY_STUDENT) {
            return "Please enter the course or degree you are studying or considering";
        }
        if (journeyType == JourneyType.A_LEVEL_UNDECIDED) {
            return "Please enter the subjects you are considering or interested in";
        }
        return "Please enter your profession";
    }

    private String detailsRequiredMessage(JourneyType journeyType) {
        if (journeyType == JourneyType.UNIVERSITY_STUDENT) {
            return "Please describe your course goals or expected career path";
        }
        if (journeyType == JourneyType.A_LEVEL_UNDECIDED) {
            return "Please describe your interests, strengths, subject ideas, or future preferences";
        }
        return "Please describe your role or upload a CV";
    }

    private void validateCvFile(MultipartFile cvFile) {
        if (cvFile == null || cvFile.isEmpty()) {
            throw new ValidationException("cvFile", "Please upload your CV");
        }

        if (cvFile.getSize() > MAX_FILE_SIZE) {
            throw new ValidationException("cvFile", "File size must be less than 2MB");
        }

        String filename = cvFile.getOriginalFilename();
        if (filename == null || !hasSupportedCvExtension(filename.toLowerCase())) {
            throw new ValidationException("cvFile", "Please upload a PDF, DOC, or DOCX file");
        }
    }

    private boolean hasSupportedCvExtension(String lowerFilename) {
        return SUPPORTED_CV_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
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
