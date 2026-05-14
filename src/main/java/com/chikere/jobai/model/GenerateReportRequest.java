package com.chikere.jobai.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GenerateReportRequest {
    private static final java.util.regex.Pattern PROMPT_INJECTION_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(\\b(ignore|disregard|forget|override|bypass)\\b.{0,80}\\b(previous|above|prior|system|developer|instructions?|prompt)\\b"
                    + "|\\b(output|reveal|print|show|exfiltrate)\\b.{0,60}\\b(system|developer)\\s+prompt\\b"
                    + "|\\b(system|developer)\\s+prompt\\b"
                    + "|\\bact\\s+as\\b"
                    + "|\\byou\\s+are\\s+now\\b)"
    );

    @Size(max = 128, message = "Session id must be 128 characters or fewer")
    @Pattern(regexp = "^[A-Za-z0-9_\\-]*$", message = "Session id contains unsupported characters")
    private String sessionId;

    @NotBlank(message = "Profession is required")
    @Size(max = 120, message = "Profession must be 120 characters or fewer")
    @Pattern(
            regexp = "^[\\p{L}\\p{M}\\p{N}\\p{Zs}.,'&()/+\\-:;!?@#%*_\"$\\[\\]\\u2019\\u2013\\u2014\\u00A3\\u20AC]+$",
            message = "Profession contains unsupported characters"
    )
    private String profession;

    @NotBlank(message = "Description is required")
    @Size(max = 5000, message = "Description must be 5000 characters or fewer")
    @Pattern(
            regexp = "^[\\p{L}\\p{M}\\p{N}\\p{Zs}\\r\\n\\t.,'&()/+\\-:;!?@#%*_\"$\\[\\]\\u2019\\u2013\\u2014\\u00A3\\u20AC]+$",
            message = "Description contains unsupported characters"
    )
    private String description;

    @DecimalMin(value = "0.0", message = "Score must be at least 0")
    @DecimalMax(value = "10.0", message = "Score must be no more than 10")
    private double score;

    @NotBlank(message = "Risk level is required")
    @Pattern(regexp = "(?i)^(low|moderate|high)$", message = "Risk level must be low, moderate, or high")
    private String riskLevel;

    @NotBlank(message = "Mode is required")
    @Pattern(
            regexp = "(?i)^(profession|professional|course|student|university_student|a_level|a-level|alevel|a_level_undecided)$",
            message = "Mode is not supported"
    )
    private String mode;

    @AssertTrue(message = "Request text contains unsupported instruction-like content")
    public boolean isPromptSafe() {
        return !containsPromptInjection(profession) && !containsPromptInjection(description);
    }

    private boolean containsPromptInjection(String value) {
        return value != null && PROMPT_INJECTION_PATTERN.matcher(value).find();
    }
}
