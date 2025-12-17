package com.chikere.jobai.model;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
public class RiskAssessmentForm {

    @NotBlank(message = "Assessment mode is required")
    private String mode; // "profession" or "course"

    @NotBlank(message = "This field is required")
    @Size(max = 100, message = "Must be less than 100 characters")
    private String profession;

    @NotBlank(message = "This field is required")
    @Size(max = 1000, message = "Must be less than 1000 characters")
    private String roleSummary;
}